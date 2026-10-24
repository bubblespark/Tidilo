package me.bubblespark.tidilo.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportNotificationDaoTest {

    private class FakeImportNotificationDao : ImportNotificationDao {
        private val list = mutableListOf<ImportNotificationEntity>()
        private val state = MutableStateFlow<List<ImportNotificationEntity>>(emptyList())
        private var nextId = 1L

        private fun emit() {
            state.value = list.sortedByDescending { it.createdAt }
        }

        override fun observeNotifications(): Flow<List<ImportNotificationEntity>> = state

        override fun observeUnreadCount(): Flow<Int> = state.map { it.count { item -> !item.isRead } }

        override suspend fun insert(notification: ImportNotificationEntity): Long {
            val assignedId = if (notification.id == 0L) nextId++ else notification.id
            val stored = notification.copy(id = assignedId)
            list.removeAll { it.id == assignedId }
            list.add(stored)
            emit()
            return assignedId
        }

        override suspend fun markAllAsRead() {
            val updated = list.map { it.copy(isRead = true) }
            list.clear()
            list.addAll(updated)
            emit()
        }

        override suspend fun deleteAll() {
            list.clear()
            emit()
        }

        override suspend fun deleteById(id: Long) {
            list.removeAll { it.id == id }
            emit()
        }
    }

    @Test
    fun testInsertAndObserveNotifications() = runBlocking {
        val dao = FakeImportNotificationDao()

        dao.insert(
            ImportNotificationEntity(
                type = "INFO",
                domain = "example.com",
                url = "https://example.com/1",
                message = "Promoted from RSS to Library",
                createdAt = 1000L
            )
        )
        dao.insert(
            ImportNotificationEntity(
                type = "WARNING",
                domain = "example.com",
                url = "https://example.com/2",
                message = "Duplicate URL (Already in Library)",
                createdAt = 2000L
            )
        )

        val notifications = dao.observeNotifications().first()
        assertEquals(2, notifications.size)
        // Check sorting by createdAt descending
        assertEquals("https://example.com/2", notifications[0].url)
        assertEquals("https://example.com/1", notifications[1].url)

        val unread = dao.observeUnreadCount().first()
        assertEquals(2, unread)
    }

    @Test
    fun testMarkAllAsRead() = runBlocking {
        val dao = FakeImportNotificationDao()
        dao.insert(
            ImportNotificationEntity(
                type = "WARNING",
                domain = "test.org",
                url = "https://test.org",
                message = "Duplicate",
                isRead = false
            )
        )

        assertEquals(1, dao.observeUnreadCount().first())

        dao.markAllAsRead()

        assertEquals(0, dao.observeUnreadCount().first())
        val list = dao.observeNotifications().first()
        assertTrue(list.all { it.isRead })
    }

    @Test
    fun testDeleteAllAndById() = runBlocking {
        val dao = FakeImportNotificationDao()
        val id1 = dao.insert(
            ImportNotificationEntity(type = "INFO", domain = "a.com", url = "https://a.com", message = "A")
        )
        val id2 = dao.insert(
            ImportNotificationEntity(type = "INFO", domain = "b.com", url = "https://b.com", message = "B")
        )

        assertEquals(2, dao.observeNotifications().first().size)

        dao.deleteById(id1)
        val afterSingleDelete = dao.observeNotifications().first()
        assertEquals(1, afterSingleDelete.size)
        assertEquals(id2, afterSingleDelete[0].id)

        dao.deleteAll()
        assertTrue(dao.observeNotifications().first().isEmpty())
    }
}
