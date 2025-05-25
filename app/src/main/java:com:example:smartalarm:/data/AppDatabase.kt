import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(
    entities = [NightRecord::class, ScheduledNight::class, EventRecord::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nightRecordDao(): NightRecordDao
    abstract fun scheduledNightDao(): ScheduledNightDao
    abstract fun eventRecordDao(): EventRecordDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext,
                AppDatabase::class.java, "smart_alarm.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}