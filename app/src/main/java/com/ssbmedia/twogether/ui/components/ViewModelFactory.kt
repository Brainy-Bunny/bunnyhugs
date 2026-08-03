package com.ssbmedia.twogether.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Tiny generic ViewModel factory so screens can build their ViewModel from ServiceLocator without a DI framework. */
class SimpleViewModelFactory(private val creator: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}
