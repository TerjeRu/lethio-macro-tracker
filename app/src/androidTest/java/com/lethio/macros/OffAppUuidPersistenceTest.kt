package com.lethio.macros

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OffAppUuidPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearBefore() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @After
    fun clearAfter() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    @Test
    fun concurrentFirstUseCreatesOnePersistentRawUuid() = runTest {
        val manager = SettingsManager(context)
        val values = (1..64).map { async(Dispatchers.Default) { manager.offAppUuid() } }.awaitAll()

        assertThat(values.toSet()).hasSize(1)
        val raw = values.toSet().single()
        assertThat(UUID.fromString(raw).toString()).isEqualTo(raw)
        assertThat(context.dataStore.data.first()[SettingsManager.OFF_APP_UUID_KEY]).isEqualTo(raw)
        assertThat(SettingsManager(context).offAppUuid()).isEqualTo(raw)
    }

    @Test
    fun lookupAndContributionConsentRemainIndependent() = runTest {
        val manager = SettingsManager(context)
        manager.setOffLookupEnabled(true)
        assertThat(manager.offLookupEnabled.first()).isTrue()
        assertThat(manager.offContributeEnabled.first()).isFalse()

        manager.setOffContributeEnabled(true)
        manager.setOffLookupEnabled(false)
        assertThat(manager.offLookupEnabled.first()).isFalse()
        assertThat(manager.offContributeEnabled.first()).isTrue()
    }
}
