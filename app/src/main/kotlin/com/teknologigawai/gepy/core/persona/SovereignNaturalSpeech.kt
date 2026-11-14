package com.teknologigawai.gepy.core.persona

/**
 * Mesin gaya bahasa manusiawi deterministik untuk seluruh respons **Fast-Path & Klip Offline** Gepy v6.6.0.
 *
 * Ketentuan desain:
 * 1. **100% Deterministik (tanpa `Random`)**: Hash SHA-256 pada `VoiceAudioCacheManager` selalu
 *    stabil sehingga kalimat yang sering diulang otomatis menjadi klip offline permanen (`0 credit`).
 * 2. **Bebas Bahasa Korporat**: Nol kata `"Anda"`, `"Status pemutaran media diubah"`, atau gaya robotik kaku;
 *    dirancang natural saat diucapkan oleh suara custom manusia asli (`eleven_v4_turbo`).
 * 3. **Utamakan Nama Pengguna & Sadar Privacy Shield**: Pada `ROMANTIC_GIRLFRIEND`, memprioritaskan
 *    memanggil langsung nama pemilik ([SovereignSpeechContext.resolvedOwnerName]) dengan hangat dan akrab,
 *    bukan memanggil "sayang"; tag ekspresi `[warmly]` / `[softly]` otomatis dinonaktifkan saat ada tamu.
 */
object SovereignNaturalSpeech {
    fun spotifyPlay(ctx: SovereignSpeechContext, query: String, targetDevice: String): String = SovereignSpeechSystemTemplates.spotifyPlay(ctx, query, targetDevice)
    fun spotifyError(ctx: SovereignSpeechContext, detail: String?): String = SovereignSpeechSystemTemplates.spotifyError(ctx, detail)
    fun youtubePlay(ctx: SovereignSpeechContext, query: String): String = SovereignSpeechSystemTemplates.youtubePlay(ctx, query)
    fun mediaControl(ctx: SovereignSpeechContext, action: String, targetDevice: String): String = SovereignSpeechSystemTemplates.mediaControl(ctx, action, targetDevice)
    fun mediaControlError(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.mediaControlError(ctx)
    fun appOpened(ctx: SovereignSpeechContext, appName: String): String = SovereignSpeechSystemTemplates.appOpened(ctx, appName)
    fun appNotFound(ctx: SovereignSpeechContext, appName: String): String = SovereignSpeechSystemTemplates.appNotFound(ctx, appName)
    fun appNotInstalled(ctx: SovereignSpeechContext, appName: String): String = SovereignSpeechSystemTemplates.appNotInstalled(ctx, appName)
    fun appLaunchSimulated(ctx: SovereignSpeechContext, resolvedPackage: String): String = SovereignSpeechSystemTemplates.appLaunchSimulated(ctx, resolvedPackage)
    fun deviceStatusSummary(
        ctx: SovereignSpeechContext,
        formattedDateTimeId: String,
        batteryPercent: Int,
        isCharging: Boolean,
        networkType: String,
        mediaVolumePercent: Int,
        ringerMode: String
    ): String =
        SovereignSpeechSystemTemplates.deviceStatusSummary(ctx, formattedDateTimeId, batteryPercent, isCharging, networkType, mediaVolumePercent, ringerMode)
    fun browserOpened(ctx: SovereignSpeechContext, urlOrQuery: String, success: Boolean): String = SovereignSpeechSystemTemplates.browserOpened(ctx, urlOrQuery, success)
    fun contactsNotFound(ctx: SovereignSpeechContext, query: String): String = SovereignSpeechSystemTemplates.contactsNotFound(ctx, query)
    fun notificationsPrivacyBlocked(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.notificationsPrivacyBlocked(ctx)
    fun notificationsEmpty(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.notificationsEmpty(ctx)
    fun locationFound(
        ctx: SovereignSpeechContext,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        provider: String
    ): String =
        SovereignSpeechSystemTemplates.locationFound(ctx, latitude, longitude, accuracyMeters, provider)
    fun locationUnavailable(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.locationUnavailable(ctx)
    fun alarmSet(ctx: SovereignSpeechContext, hour: Int, minute: Int, label: String, success: Boolean): String = SovereignSpeechSystemTemplates.alarmSet(ctx, hour, minute, label, success)
    fun wellnessEmptyFallback(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.wellnessEmptyFallback(ctx)
    fun deepLinkWhatsAppDraft(ctx: SovereignSpeechContext, contactName: String): String = SovereignSpeechSystemTemplates.deepLinkWhatsAppDraft(ctx, contactName)
    fun deepLinkSettingsOpened(ctx: SovereignSpeechContext, panelTarget: String): String = SovereignSpeechSystemTemplates.deepLinkSettingsOpened(ctx, panelTarget)
    fun deepLinkSettingsUnknown(ctx: SovereignSpeechContext, panelTarget: String): String = SovereignSpeechSystemTemplates.deepLinkSettingsUnknown(ctx, panelTarget)
    fun deepLinkMapsNavigation(ctx: SovereignSpeechContext, destination: String): String = SovereignSpeechSystemTemplates.deepLinkMapsNavigation(ctx, destination)
    fun deepLinkMapsSearch(ctx: SovereignSpeechContext, place: String): String = SovereignSpeechSystemTemplates.deepLinkMapsSearch(ctx, place)
    fun mapsOpenError(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.mapsOpenError(ctx)
    fun torchToggled(ctx: SovereignSpeechContext, enabled: Boolean, success: Boolean): String = SovereignSpeechSystemTemplates.torchToggled(ctx, enabled, success)
    fun timerSet(
        ctx: SovereignSpeechContext,
        durationLabel: String,
        label: String,
        success: Boolean
    ): String =
        SovereignSpeechSystemTemplates.timerSet(ctx, durationLabel, label, success)
    fun alarmsDisplayed(
        ctx: SovereignSpeechContext,
        nextAlarmFormatted: String?,
        openedUi: Boolean
    ): String =
        SovereignSpeechSystemTemplates.alarmsDisplayed(ctx, nextAlarmFormatted, openedUi)
    fun volumeAdjusted(
        ctx: SovereignSpeechContext,
        streamLabelId: String,
        percent: Int?,
        directionLabel: String?
    ): String =
        SovereignSpeechSystemTemplates.volumeAdjusted(ctx, streamLabelId, percent, directionLabel)
    fun volumeError(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.volumeError(ctx)
    fun ringerModeChanged(
        ctx: SovereignSpeechContext,
        modeLabel: String,
        needsDndPermission: Boolean
    ): String =
        SovereignSpeechSystemTemplates.ringerModeChanged(ctx, modeLabel, needsDndPermission)
    fun dialerOpened(ctx: SovereignSpeechContext, contactLabel: String, success: Boolean): String = SovereignSpeechSystemTemplates.dialerOpened(ctx, contactLabel, success)
    fun emailComposerOpened(ctx: SovereignSpeechContext, recipientLabel: String, success: Boolean): String = SovereignSpeechSystemTemplates.emailComposerOpened(ctx, recipientLabel, success)
    fun floatingPanelOpened(ctx: SovereignSpeechContext, panelLabel: String, success: Boolean): String = SovereignSpeechSystemTemplates.floatingPanelOpened(ctx, panelLabel, success)
    fun nowPlayingInfo(
        ctx: SovereignSpeechContext,
        title: String,
        artist: String,
        appLabel: String,
        isPlaying: Boolean
    ): String =
        SovereignSpeechSystemTemplates.nowPlayingInfo(ctx, title, artist, appLabel, isPlaying)
    fun nowPlayingEmpty(ctx: SovereignSpeechContext, hasPermission: Boolean): String = SovereignSpeechSystemTemplates.nowPlayingEmpty(ctx, hasPermission)
    fun notificationReplied(ctx: SovereignSpeechContext, senderName: String): String = SovereignSpeechSystemTemplates.notificationReplied(ctx, senderName)
    fun notificationReplyConfirmPrompt(
        ctx: SovereignSpeechContext,
        senderName: String,
        replyMessage: String
    ): String =
        SovereignSpeechSystemTemplates.notificationReplyConfirmPrompt(ctx, senderName, replyMessage)
    fun notificationDismissed(
        ctx: SovereignSpeechContext,
        dismissedCount: Int,
        dismissedLabels: List<String>
    ): String =
        SovereignSpeechSystemTemplates.notificationDismissed(ctx, dismissedCount, dismissedLabels)
    fun notificationAccessRequired(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.notificationAccessRequired(ctx)
    fun notificationTargetNotFound(ctx: SovereignSpeechContext, targetQuery: String): String = SovereignSpeechSystemTemplates.notificationTargetNotFound(ctx, targetQuery)
    fun notificationsSafetyBlocked(ctx: SovereignSpeechContext): String = SovereignSpeechSystemTemplates.notificationsSafetyBlocked(ctx)

    fun memorySaved(ctx: SovereignSpeechContext, content: String): String = SovereignSpeechPersonalTemplates.memorySaved(ctx, content)
    fun memoryNoScheduleToday(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryNoScheduleToday(ctx)
    fun memoryScheduleToday(ctx: SovereignSpeechContext, itemsText: String): String = SovereignSpeechPersonalTemplates.memoryScheduleToday(ctx, itemsText)
    fun memoryRememberedFact(ctx: SovereignSpeechContext, formattedFact: String): String = SovereignSpeechPersonalTemplates.memoryRememberedFact(ctx, formattedFact)
    fun memoryMissingRelation(ctx: SovereignSpeechContext, relation: String): String = SovereignSpeechPersonalTemplates.memoryMissingRelation(ctx, relation)
    fun memoryMissingWork(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingWork(ctx)
    fun memoryMissingLocation(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingLocation(ctx)
    fun memoryHealthList(ctx: SovereignSpeechContext, itemsJoined: String): String = SovereignSpeechPersonalTemplates.memoryHealthList(ctx, itemsJoined)
    fun memoryMissingHealth(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingHealth(ctx)
    fun memoryBirthday(ctx: SovereignSpeechContext, formattedFact: String): String = SovereignSpeechPersonalTemplates.memoryBirthday(ctx, formattedFact)
    fun memoryMissingBirthday(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingBirthday(ctx)
    fun memoryMissingVehicle(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingVehicle(ctx)
    fun memoryMissingRoutine(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingRoutine(ctx)
    fun memoryMissingFoodDrink(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingFoodDrink(ctx)
    fun memoryMissingBoss(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingBoss(ctx)
    fun memoryPrefList(ctx: SovereignSpeechContext, itemsJoined: String): String = SovereignSpeechPersonalTemplates.memoryPrefList(ctx, itemsJoined)
    fun memoryMissingPref(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingPref(ctx)
    fun memoryEduFact(ctx: SovereignSpeechContext, formattedFact: String): String = SovereignSpeechPersonalTemplates.memoryEduFact(ctx, formattedFact)
    fun memoryMissingEdu(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingEdu(ctx)
    fun memoryMissingTechProfile(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingTechProfile(ctx)
    fun memoryProfileSummary(ctx: SovereignSpeechContext, displayName: String, factsJoined: String?): String = SovereignSpeechPersonalTemplates.memoryProfileSummary(ctx, displayName, factsJoined)
    fun memoryMissingPet(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingPet(ctx)
    fun memoryMissingDevice(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingDevice(ctx)
    fun memoryMissingProject(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingProject(ctx)
    fun memoryGoalWithSpirit(ctx: SovereignSpeechContext, formattedFact: String): String = SovereignSpeechPersonalTemplates.memoryGoalWithSpirit(ctx, formattedFact)
    fun memoryMissingGoal(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingGoal(ctx)
    fun memoryMissingDietary(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.memoryMissingDietary(ctx)
    fun memoryGeneralRecall(ctx: SovereignSpeechContext, factsJoined: String): String = SovereignSpeechPersonalTemplates.memoryGeneralRecall(ctx, factsJoined)
    fun memoryGeneralMissing(ctx: SovereignSpeechContext, queryCore: String): String = SovereignSpeechPersonalTemplates.memoryGeneralMissing(ctx, queryCore)
    fun screenSummaryLauncher(ctx: SovereignSpeechContext, appNames: List<String>): String = SovereignSpeechPersonalTemplates.screenSummaryLauncher(ctx, appNames)
    fun screenSummaryEmpty(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.screenSummaryEmpty(ctx)
    fun screenSummaryGeneric(ctx: SovereignSpeechContext, topTexts: String): String = SovereignSpeechPersonalTemplates.screenSummaryGeneric(ctx, topTexts)
    fun screenSummaryWhatsAppChat(
        ctx: SovereignSpeechContext,
        contactTitle: String?,
        lastMsg: String?
    ): String =
        SovereignSpeechPersonalTemplates.screenSummaryWhatsAppChat(ctx, contactTitle, lastMsg)
    fun screenSummaryWhatsAppList(ctx: SovereignSpeechContext, chatNames: List<String>): String = SovereignSpeechPersonalTemplates.screenSummaryWhatsAppList(ctx, chatNames)
    fun screenSummaryWhatsAppOther(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.screenSummaryWhatsAppOther(ctx)
    fun whatsappSent(ctx: SovereignSpeechContext, targetName: String): String = SovereignSpeechPersonalTemplates.whatsappSent(ctx, targetName)
    fun whatsappDisambiguate(ctx: SovereignSpeechContext, targetName: String): String = SovereignSpeechPersonalTemplates.whatsappDisambiguate(ctx, targetName)
    fun whatsappConfirmPrompt(ctx: SovereignSpeechContext, targetName: String, message: String): String = SovereignSpeechPersonalTemplates.whatsappConfirmPrompt(ctx, targetName, message)
    fun whatsappGenericConfirm(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.whatsappGenericConfirm(ctx)
    fun instagramDmConfirm(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.instagramDmConfirm(ctx)
    fun instagramStoryShared(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.instagramStoryShared(ctx)
    fun laptopLockFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopLockFallback(ctx)
    fun laptopSleepFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopSleepFallback(ctx)
    fun laptopShutdownAskConfirm(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopShutdownAskConfirm(ctx)
    fun laptopShutdownConfirmedFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopShutdownConfirmedFallback(ctx)
    fun laptopShutdownConfirmExpired(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopShutdownConfirmExpired(ctx)
    fun laptopShutdownCancelledLocal(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopShutdownCancelledLocal(ctx)
    fun laptopShutdownCancelledFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopShutdownCancelledFallback(ctx)
    fun laptopVolumeFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopVolumeFallback(ctx)
    fun laptopOpenAppFallback(ctx: SovereignSpeechContext, targetApp: String): String = SovereignSpeechPersonalTemplates.laptopOpenAppFallback(ctx, targetApp)
    fun laptopStatusFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopStatusFallback(ctx)
    fun laptopClipboardFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopClipboardFallback(ctx)
    fun laptopMediaFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopMediaFallback(ctx)
    fun laptopGenericFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.laptopGenericFallback(ctx)
    fun dailyBriefingMorning(
        ctx: SovereignSpeechContext,
        dayLabel: String,
        hhmm: String,
        todaySchedulesText: String?,
        yesterdaySteps: Int?,
        batteryPercent: Int,
        isCharging: Boolean
    ): String =
        SovereignSpeechPersonalTemplates.dailyBriefingMorning(ctx, dayLabel, hhmm, todaySchedulesText, yesterdaySteps, batteryPercent, isCharging)
    fun dailyBriefingEvening(
        ctx: SovereignSpeechContext,
        todaySteps: Int,
        socialMins: Int,
        scheduleCount: Int,
        batteryPercent: Int,
        isCharging: Boolean
    ): String =
        SovereignSpeechPersonalTemplates.dailyBriefingEvening(ctx, todaySteps, socialMins, scheduleCount, batteryPercent, isCharging)
    fun dailyBriefingDaytime(
        ctx: SovereignSpeechContext,
        hhmm: String,
        todaySchedulesText: String?,
        todaySteps: Int,
        socialMins: Int,
        batteryPercent: Int
    ): String =
        SovereignSpeechPersonalTemplates.dailyBriefingDaytime(ctx, hhmm, todaySchedulesText, todaySteps, socialMins, batteryPercent)
    fun gatekeeperGreeting(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.gatekeeperGreeting(ctx)
    fun gatekeeperDestructiveConfirm(ctx: SovereignSpeechContext, cleanGoal: String): String = SovereignSpeechPersonalTemplates.gatekeeperDestructiveConfirm(ctx, cleanGoal)
    fun gatekeeperDisambiguate(ctx: SovereignSpeechContext, count: Int, candidateTarget: String): String = SovereignSpeechPersonalTemplates.gatekeeperDisambiguate(ctx, count, candidateTarget)
    fun orchestratorDoneFallback(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.orchestratorDoneFallback(ctx)
    fun orchestratorDisambiguateDefault(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.orchestratorDisambiguateDefault(ctx)
    fun orchestratorConfirmDefault(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.orchestratorConfirmDefault(ctx)
    fun orchestratorMaxSteps(ctx: SovereignSpeechContext, maxSteps: Int): String = SovereignSpeechPersonalTemplates.orchestratorMaxSteps(ctx, maxSteps)
    fun orchestratorVoiceListenStarted(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.orchestratorVoiceListenStarted(ctx)
    fun orchestratorFullAccessChanged(ctx: SovereignSpeechContext, enabled: Boolean): String = SovereignSpeechPersonalTemplates.orchestratorFullAccessChanged(ctx, enabled)
    fun orchestratorTaskCancelled(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.orchestratorTaskCancelled(ctx)
    fun orchestratorError(ctx: SovereignSpeechContext, detail: String): String = SovereignSpeechPersonalTemplates.orchestratorError(ctx, detail)
    fun liveCallEnded(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.liveCallEnded(ctx)
    fun liveCallListeningPrompt(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.liveCallListeningPrompt(ctx)
    fun liveCallConnecting(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.liveCallConnecting(ctx)
    fun liveCallConnected(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.liveCallConnected(ctx)
    fun liveCallReconnecting(ctx: SovereignSpeechContext, attempt: Int, maxAttempts: Int): String = SovereignSpeechPersonalTemplates.liveCallReconnecting(ctx, attempt, maxAttempts)
    fun liveCallFailed(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.liveCallFailed(ctx)
    fun voicePreviewSample(ctx: SovereignSpeechContext): String = SovereignSpeechPersonalTemplates.voicePreviewSample(ctx)
    fun csiOwnerGreeting(ctx: SovereignSpeechContext, fallbackText: String): String = SovereignSpeechPersonalTemplates.csiOwnerGreeting(ctx, fallbackText)
}
