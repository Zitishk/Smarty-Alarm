@Dao
interface EventRecordDao {
    @Insert suspend fun insert(event: EventRecord)

    @Query("SELECT * FROM event_records ORDER BY timestamp DESC LIMIT 100")
    suspend fun recent(): List<EventRecord>
}