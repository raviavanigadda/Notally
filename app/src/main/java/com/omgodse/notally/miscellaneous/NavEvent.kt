package com.omgodse.notally.miscellaneous

import androidx.navigation.NavDirections

sealed class NavEvent{
    data class ToDirection(val directions: NavDirections) : NavEvent()
    object Back : NavEvent()
}