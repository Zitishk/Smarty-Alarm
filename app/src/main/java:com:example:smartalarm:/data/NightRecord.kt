import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "night_records")
data class NightRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hd: String,
    val pw: String?,
    val eb: String,
    val priority: String,
    val e0: Int,
    val rg: Double,
    val sl: Int,
    val outcome: String,
    val retries: Int,
    val snoozes: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val cycleEst: Double
)