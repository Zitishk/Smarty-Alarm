import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "event_records")
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "RETRY" or "SNOOZE"
    val timestamp: Long = System.currentTimeMillis()
)