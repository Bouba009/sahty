package com.sahty.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.room.*
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class ReminderType {
    MEDICATION, WATER, BLOOD_PRESSURE, BLOOD_SUGAR, DOCTOR_APPOINTMENT
}

enum class MedicalDocCategory {
    LAB_TEST, PRESCRIPTION, SCAN_ECHO_IRM
}

@Entity(tableName = "health_reminders")
data class HealthReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ReminderType,
    val title: String,
    val triggerTimeMillis: Long,
    val isTakenOrCompleted: Boolean = false
)

@Entity(tableName = "vitals_logs")
data class VitalsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metricType: String,
    val primaryValue: Double,
    val secondaryValue: Double? = null,
    val recordedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "medical_vault_files")
data class MedicalVaultFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: MedicalDocCategory,
    val title: String,
    val encryptedFilePath: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface HealthDao {
    @Insert
    suspend fun insertReminder(reminder: HealthReminderEntity): Long

    @Query("SELECT * FROM health_reminders WHERE id = :id")
    suspend fun getReminderById(id: Long): HealthReminderEntity?

    @Update
    suspend fun updateReminder(reminder: HealthReminderEntity)

    @Insert
    suspend fun insertVital(vital: VitalsLogEntity): Long

    @Insert
    suspend fun insertMedicalFile(file: MedicalVaultFileEntity): Long
}

@Database(
    entities = [HealthReminderEntity::class, VitalsLogEntity::class, MedicalVaultFileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object {
        @Volatile private var INSTANCE: HealthDatabase? = null
        fun getInstance(context: Context): HealthDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HealthDatabase::class.java,
                    "sahty_health.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

object NotificationChannels {
    const val HEALTH_ALERTS = "sahty_refined_alerts_v1"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val resId = context.resources.getIdentifier("refined_chime", "raw", context.packageName)
            val soundUri: Uri = if (resId != 0) {
                Uri.parse("android.resource://${context.packageName}/$resId")
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                HEALTH_ALERTS,
                "تنبيهات صحتي والمواعيد",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(soundUri, audioAttributes)
                enableVibration(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

class MedicationInitialWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(MedicationFollowUpWorker.KEY_REMINDER_ID, -1L)
        val medTitle = inputData.getString("med_title") ?: "الدواء"
        if (reminderId == -1L) return Result.failure()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.HEALTH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("موعد الدواء: $medTitle")
            .setContentText("حان الآن موعد تناول دوائك.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify((reminderId + 10000).toInt(), notification)
        MedicationFollowUpWorker.scheduleFollowUp(applicationContext, reminderId, delayMinutes = 30)
        return Result.success()
    }
}

class MedicationFollowUpWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
        if (reminderId == -1L) return Result.failure()

        val db = HealthDatabase.getInstance(applicationContext)
        val reminder = db.healthDao().getReminderById(reminderId) ?: return Result.success()
        if (reminder.isTakenOrCompleted) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val yesIntent = Intent(applicationContext, MedActionReceiver::class.java).apply {
            action = ACTION_MED_YES
            putExtra(KEY_REMINDER_ID, reminderId)
        }
        val noIntent = Intent(applicationContext, MedActionReceiver::class.java).apply {
            action = ACTION_MED_NO
            putExtra(KEY_REMINDER_ID, reminderId)
        }

        val yesPending = PendingIntent.getBroadcast(
            applicationContext,
            (reminderId * 10 + 1).toInt(),
            yesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noPending = PendingIntent.getBroadcast(
            applicationContext,
            (reminderId * 10 + 2).toInt(),
            noIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.HEALTH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("متابعة الدواء: ${reminder.title}")
            .setContentText("هل شربت دواءك؟")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(0, "نعم", yesPending)
            .addAction(0, "لا", noPending)
            .build()

        manager.notify(reminderId.toInt(), notification)
        scheduleFollowUp(applicationContext, reminderId, delayMinutes = 30)
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val ACTION_MED_YES = "com.sahty.ACTION_MED_YES"
        const val ACTION_MED_NO = "com.sahty.ACTION_MED_NO"

        fun scheduleMedication(context: Context, reminderId: Long, medTitle: String, delayMinutes: Long) {
            val data = workDataOf(KEY_REMINDER_ID to reminderId, "med_title" to medTitle)
            val request = OneTimeWorkRequestBuilder<MedicationInitialWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun scheduleFollowUp(context: Context, reminderId: Long, delayMinutes: Long = 30) {
            val data = workDataOf(KEY_REMINDER_ID to reminderId)
            val request = OneTimeWorkRequestBuilder<MedicationFollowUpWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "med_followup_$reminderId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class MedActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(MedicationFollowUpWorker.KEY_REMINDER_ID, -1L)
        if (reminderId == -1L) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(reminderId.toInt())

        when (intent.action) {
            MedicationFollowUpWorker.ACTION_MED_YES -> {
                WorkManager.getInstance(context).cancelUniqueWork("med_followup_$reminderId")
                CoroutineScope(Dispatchers.IO).launch {
                    val db = HealthDatabase.getInstance(context)
                    val existing = db.healthDao().getReminderById(reminderId)
                    if (existing != null) {
                        db.healthDao().updateReminder(existing.copy(isTakenOrCompleted = true))
                    }
                }
            }
            MedicationFollowUpWorker.ACTION_MED_NO -> {
                MedicationFollowUpWorker.scheduleFollowUp(context, reminderId, delayMinutes = 30)
            }
        }
    }
}

class GeneralHealthReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: "تذكير صحي"
        val body = inputData.getString("body") ?: "حان موعد المتابعة الصحية."
        val notifId = inputData.getInt("notif_id", System.currentTimeMillis().toInt())

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, NotificationChannels.HEALTH_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notif)
        return Result.success()
    }

    companion object {
        fun scheduleWaterReminder(context: Context, intervalHours: Long = 2) {
            val data = workDataOf(
                "title" to "تذكير شرب الماء",
                "body" to "حان وقت شرب كوب من الماء.",
                "notif_id" to 2001
            )
            val request = PeriodicWorkRequestBuilder<GeneralHealthReminderWorker>(
                intervalHours, TimeUnit.HOURS
            ).setInputData(data).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "water_periodic_reminder",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleDoctorAppointment(context: Context, doctorName: String, hoursFromNow: Long) {
            val appointmentMillis = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hoursFromNow)
            val now = System.currentTimeMillis()
            val oneDayBefore = appointmentMillis - TimeUnit.DAYS.toMillis(1)

            if (oneDayBefore > now) {
                val dayBeforeData = workDataOf(
                    "title" to "تذكير مبكر: موعد مع $doctorName غداً",
                    "body" to "يرجى تجهيز التحاليل والوصفات وصور Scanner / IRM قبل موعد الغد.",
                    "notif_id" to 4001
                )
                val dayBeforeWork = OneTimeWorkRequestBuilder<GeneralHealthReminderWorker>()
                    .setInitialDelay(oneDayBefore - now, TimeUnit.MILLISECONDS)
                    .setInputData(dayBeforeData)
                    .build()
                WorkManager.getInstance(context).enqueue(dayBeforeWork)
            }

            val todayData = workDataOf(
                "title" to "اليوم موعدك مع $doctorName",
                "body" to "لا تنسَ اصطحاب ملفك الطبي والتحاليل.",
                "notif_id" to 4002
            )
            val todayWork = OneTimeWorkRequestBuilder<GeneralHealthReminderWorker>()
                .setInitialDelay(appointmentMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(todayData)
                .build()
            WorkManager.getInstance(context).enqueue(todayWork)
        }
    }
}

class PrivateMedicalStorage(private val context: Context) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val vaultDir: File by lazy {
        File(context.filesDir, "sahty_private_vault").apply {
            if (!exists()) mkdirs()
        }
    }

    fun saveEncryptedDocument(inputStream: InputStream, category: MedicalDocCategory): String {
        val targetFile = File(vaultDir, "${category.name}_${UUID.randomUUID()}.enc")
        val encryptedFile = EncryptedFile.Builder(
            context,
            targetFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

        encryptedFile.openFileOutput().use { output ->
            inputStream.copyTo(output)
        }
        return targetFile.absolutePath
    }
}

class SahtyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SahtyHomeScreen()
            }
        }
    }
}

@Composable
fun SahtyHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { HealthDatabase.getInstance(context) }
    val storage = remember { PrivateMedicalStorage(context) }

    var medName by remember { mutableStateOf("") }
    var medDelayMin by remember { mutableStateOf("1") }
    var bpSys by remember { mutableStateOf("") }
    var bpDia by remember { mutableStateOf("") }
    var glucose by remember { mutableStateOf("") }
    var doctorName by remember { mutableStateOf("") }
    var doctorHours by remember { mutableStateOf("26") }
    var selectedCategory by remember { mutableStateOf(MedicalDocCategory.LAB_TEST) }
    var statusMsg by remember { mutableStateOf("جاهز") }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val path = storage.saveEncryptedDocument(stream, selectedCategory)
                    db.healthDao().insertMedicalFile(
                        MedicalVaultFileEntity(
                            category = selectedCategory,
                            title = selectedCategory.name,
                            encryptedFilePath = path
                        )
                    )
                    statusMsg = "تم حفظ الملف الطبي في الخزنة المشفرة"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تطبيق صحتي", style = MaterialTheme.typography.headlineMedium)
        Text(statusMsg, color = MaterialTheme.colorScheme.primary)

        OutlinedTextField(value = medName, onValueChange = { medName = it }, label = { Text("اسم الدواء") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = medDelayMin, onValueChange = { medDelayMin = it }, label = { Text("التنبيه بعد (دقيقة)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                val id = db.healthDao().insertReminder(
                    HealthReminderEntity(type = ReminderType.MEDICATION, title = medName, triggerTimeMillis = System.currentTimeMillis())
                )
                MedicationFollowUpWorker.scheduleMedication(context, id, medName, medDelayMin.toLongOrNull() ?: 1L)
                statusMsg = "تمت جدولة الدواء + متابعة كل 30 دقيقة"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("حفظ موعد الدواء")
        }

        Button(onClick = {
            GeneralHealthReminderWorker.scheduleWaterReminder(context, 2)
            statusMsg = "تم تفعيل تذكير شرب الماء كل ساعتين"
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تفعيل تذكير شرب الماء")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = bpSys, onValueChange = { bpSys = it }, label = { Text("الضغط الانقباضي") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = bpDia, onValueChange = { bpDia = it }, label = { Text("الانبساطي") }, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                db.healthDao().insertVital(VitalsLogEntity(metricType = "BP", primaryValue = bpSys.toDoubleOrNull() ?: 0.0, secondaryValue = bpDia.toDoubleOrNull()))
                statusMsg = "تم تسجيل ضغط الدم"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تدوين ضغط الدم")
        }

        OutlinedTextField(value = glucose, onValueChange = { glucose = it }, label = { Text("قياس السكري") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                db.healthDao().insertVital(VitalsLogEntity(metricType = "GLUCOSE", primaryValue = glucose.toDoubleOrNull() ?: 0.0))
                statusMsg = "تم تسجيل قياس السكري"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تدوين السكري")
        }

        OutlinedTextField(value = doctorName, onValueChange = { doctorName = it }, label = { Text("اسم الطبيب") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = doctorHours, onValueChange = { doctorHours = it }, label = { Text("الموعد بعد (ساعة)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            GeneralHealthReminderWorker.scheduleDoctorAppointment(context, doctorName, doctorHours.toLongOrNull() ?: 26L)
            statusMsg = "تم تسجيل موعد الطبيب (تنبيه قبل بيوم + يوم الموعد)"
        }, modifier = Modifier.fillMaxWidth()) {
            Text("تسجيل موعد طبيب")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { selectedCategory = MedicalDocCategory.LAB_TEST; docPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("تحليل")
            }
            Button(onClick = { selectedCategory = MedicalDocCategory.PRESCRIPTION; docPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("وصفة")
            }
            Button(onClick = { selectedCategory = MedicalDocCategory.SCAN_ECHO_IRM; docPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text("Scanner/IRM")
            }
        }
    }
}
