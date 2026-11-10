package com.flowstate.focus.service

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1.8.0: a subject another screen asks the Focus screen to start with ("Focus on this" on a syllabus
 * chapter). The Focus screen takes it once and clears it; a running session is never changed.
 */
@Singleton
class FocusPrefill @Inject constructor() {
    val subject = MutableStateFlow<String?>(null)
}
