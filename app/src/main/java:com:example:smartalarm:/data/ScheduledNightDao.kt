import androidx.room.OnConflictStrategy
import com.example.smartalarm.data.ScheduledNight

@Dao
interface ScheduledNightDao {
    @Query("SELECT * FROM scheduled_night WHERE id=1")
    suspend fun get(): ScheduledNight?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(night: ScheduledNight)
}