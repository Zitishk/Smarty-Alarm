@Entity(tableName = "scheduled_night")
data class ScheduledNight(
    @PrimaryKey val id: Int = 1,
    val hd: String,
    val pw: String?,
    val eb: String,
    val priority: String
)