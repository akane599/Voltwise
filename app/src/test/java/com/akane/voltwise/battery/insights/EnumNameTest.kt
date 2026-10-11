package com.akane.voltwise.battery.insights

import com.akane.voltwise.battery.insights.model.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnumNameTest {
    @Test fun knownActionNamesResolveToTheirValues() {
        for (action in ActionType.entries) {
            assertEquals("known action ${action.name}", action, enumName<ActionType>(action.name))
        }
    }

    @Test fun unknownAndMissingActionNamesReturnNull() {
        assertNull(enumName<ActionType>("FUTURE_ACTION"))
        assertNull(enumName<ActionType>(ActionType.FORCE_STOP.name.lowercase()))
        assertNull(enumName<ActionType>(""))
        assertNull(enumName<ActionType>(null))
    }
}
