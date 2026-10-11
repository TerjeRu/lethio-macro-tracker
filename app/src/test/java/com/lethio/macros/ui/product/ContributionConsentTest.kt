package com.lethio.macros.ui.product

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContributionConsentTest {
    @Test
    fun `each product requires an affirmative contribution choice`() {
        assertThat(CONTRIBUTION_CHECKBOX_DEFAULT).isFalse()
    }
}
