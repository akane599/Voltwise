package com.akane.voltwise.battery.util

/** Only observational commands may be replayed automatically after a lost response. */
enum class ExecutionPolicy { READ_ONLY, MUTATION }

/** UNKNOWN means a dispatched command may have taken effect without a confirmed response. */
enum class ExecutionCertainty { CONFIRMED, UNKNOWN }
