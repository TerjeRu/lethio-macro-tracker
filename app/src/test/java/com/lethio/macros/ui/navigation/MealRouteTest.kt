package com.lethio.macros.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.lethio.macros.domain.model.MealType
import org.junit.Test

/** The meal travels Diary -> Search -> logging as an optional route argument. */
class MealRouteTest {

    @Test
    fun `routes carry the meal only when one was chosen`() {
        assertThat(Screen.Search.createRoute()).isEqualTo("search")
        assertThat(Screen.Search.createRoute(MealType.LUNCH)).isEqualTo("search?meal=LUNCH")
        assertThat(Screen.LogEntry.createRoute(7, "bundled")).isEqualTo("log_entry/7/bundled")
        assertThat(Screen.LogEntry.createRoute(7, "bundled", MealType.DINNER))
            .isEqualTo("log_entry/7/bundled?meal=DINNER")
        assertThat(Screen.QuickAdd.createRoute()).isEqualTo("quick_add")
        // Names go through android.net.Uri.encode, a null-returning stub on the JVM; the
        // name+meal form is exercised on the device instead.
        assertThat(Screen.QuickAdd.createRoute(null, MealType.SNACK))
            .isEqualTo("quick_add?meal=SNACK")
    }

    @Test
    fun `the bottom bar opens plain Search, never the argument placeholder`() {
        assertThat(Screen.Search.tabRoute).isEqualTo("search")
        assertThat(Screen.Diary.tabRoute).isEqualTo(Screen.Diary.route)
    }

    @Test
    fun `an unknown meal name is ignored`() {
        assertThat(mealOrNull("BREAKFAST")).isEqualTo(MealType.BREAKFAST)
        assertThat(mealOrNull("{meal}")).isNull()
    }
}
