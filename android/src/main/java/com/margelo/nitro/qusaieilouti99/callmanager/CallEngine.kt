package com.margelo.nitro.qusaieilouti99.callmanager
import android.media.AudioFocusRequest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import android.app.KeyguardManager
import java.util.UUID
import android.provider.Settings
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * Core call‐management engine. Manages self-managed telecom calls,
 * audio routing, UI notifications, etc.
 *
 * Audio routing now uses both modern CallEndpoint API (API 34+) and legacy AudioManager (API 28-33)
 * for backward compatibility.
 */
object CallEngine {
  private const val TAG = "CallEngine"
  private const val PHONE_ACCOUNT_ID = "com.qusaieilouti99.callmanager.SELF_MANAGED"
  private const val NOTIF_CHANNEL_ID = "incoming_call_channel"
  private const val NOTIF_ID = 2001

  /**
   * Trusted display snapshot for the internal incoming-call UI.
   *
   * This data is resolved from CallEngine's own in-memory call state. It must not be
   * reconstructed from Activity launch extras, because host apps may accidentally export
   * [CallActivity] and allow untrusted external launches.
   */
  internal data class IncomingCallUiState(
    val callId: String,
    val callerName: String,
    val callType: String,
    val callerAvatarUrl: String?
  )

  // ✅ ADD THIS: Direct reference to prevent resource shrinking
  @Suppress("unused")
  private val KEEP_RINGBACK_RESOURCE = R.raw.ringback_tone

  interface CallEndListener {
    fun onCallEnded(callId: String)
  }

  private val callEndListeners = CopyOnWriteArrayList<CallEndListener>()
  private val mainHandler = Handler(Looper.getMainLooper())

  fun registerCallEndListener(l: CallEndListener) {
    callEndListeners.add(l)
  }

  fun unregisterCallEndListener(l: CallEndListener) {
    callEndListeners.remove(l)
  }

  @Volatile private var appContext: Context? = null
  private val isInitialized = AtomicBoolean(false)
  private val initializationLock = Any()

  private var ringtone: android.media.Ringtone? = null
  private var ringbackPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var audioManager: AudioManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

  private val activeCalls = ConcurrentHashMap<String, CallInfo>()
  private val telecomConnections = ConcurrentHashMap<String, Connection>()
  private val callMetadata = ConcurrentHashMap<String, String>()
  private val callAnswerStates = ConcurrentHashMap<String, Boolean>()

  private var audioFocusRequest: AudioFocusRequest? = null

  private var currentCallId: String? = null
  private var canMakeMultipleCalls: Boolean = false
  private var lockScreenBypassActive = false
  private val lockScreenBypassCallbacks = mutableSetOf<LockScreenBypassCallback>()
  private var eventHandler: ((CallEventType, String) -> Unit)? = null
  private val cachedEvents = mutableListOf<Pair<CallEventType, String>>()

  // Audio routing state for both modern and legacy systems
  // Modern (API 34+)
  private var currentActiveCallEndpoint: Any? = null // Using Any to avoid API level issues
  private var availableCallEndpoints: List<Any> = emptyList()

  // Legacy (API 28-33)
  private var legacyCurrentAudioRoute: String = "Earpiece"
  private var legacyAvailableAudioDevices: Set<String> = setOf("Earpiece", "Speaker")

  private var wasManuallySetAudioRoute: Boolean = false
  private var callStartTime: Long = 0

  // NEW: State for CALL_STATE_CHANGED event
  private var previousCallStateActive: Boolean = false

  // NEW: Manual control for idle timer (screen awake)
  private var manualIdleTimerDisabled: Boolean = false

  // NEW: Activity lifecycle tracking for robust foreground detection
  // This counts *all* activities of the app.
  private var foregroundActivitiesCount = 0 // Tracks how many of our activities are in resumed state
  private var isAppCurrentlyVisible = false // Reflects if any of our activities are in foreground

  // This specifically tracks if MainActivity is in the foreground
  private var isMainActivityCurrentlyVisible = false // NEW: Track MainActivity visibility

  // New properties for call rejection
  private val callTokens = ConcurrentHashMap<String, String>()
  private val callRejectEndpoints = ConcurrentHashMap<String, String>()
  private val httpClient = OkHttpClient() // Reuse HTTP client

  private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) {
          if (activity.packageName == appContext?.packageName) {
              foregroundActivitiesCount++
              if (foregroundActivitiesCount > 0) {
                  isAppCurrentlyVisible = true
              }
              if (activity.javaClass.simpleName == "MainActivity") {
                  isMainActivityCurrentlyVisible = true
                  Log.d(TAG, "MainActivity is now visible (resumed)")
              }
              Log.d(TAG, "App is now visible (resumed activity: ${activity.javaClass.simpleName}), total visible: $foregroundActivitiesCount")
          }
      }

      override fun onActivityPaused(activity: Activity) {
          if (activity.packageName == appContext?.packageName) {
              foregroundActivitiesCount--
              if (foregroundActivitiesCount <= 0) { // Only set to false if ALL activities are paused
                  isAppCurrentlyVisible = false
              }
              if (activity.javaClass.simpleName == "MainActivity") {
                  isMainActivityCurrentlyVisible = false
                  Log.d(TAG, "MainActivity is no longer visible (paused)")
              }
              Log.d(TAG, "App is no longer visible (paused activity: ${activity.javaClass.simpleName}), total visible: $foregroundActivitiesCount")
          }
      }

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
  }


  interface LockScreenBypassCallback {
    fun onLockScreenBypassChanged(shouldBypass: Boolean)
  }

  fun initialize(context: Context) {
    synchronized(initializationLock) {
      if (isInitialized.compareAndSet(false, true)) {
        appContext = context.applicationContext
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Register lifecycle callbacks
        (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        Log.d(TAG, "CallEngine initialized successfully")
        // Initial state emission
        updateOverallIdleTimerDisabledState()
        emitCallStateChanged()
      }
    }
  }

  fun isInitialized(): Boolean = isInitialized.get()

  private fun requireContext(): Context {
    return appContext ?: throw IllegalStateException(
      "CallEngine not initialized. Call initialize() in Application.onCreate()"
    )
  }

  fun getContext(): Context? = appContext

  fun setEventHandler(handler: ((CallEventType, String) -> Unit)?) {
    Log.d(TAG, "setEventHandler called. Handler present: ${handler != null}")
    eventHandler = handler
    handler?.let { h ->
      if (cachedEvents.isNotEmpty()) {
        Log.d(TAG, "Emitting ${cachedEvents.size} cached events.")
        cachedEvents.forEach { (type, data) -> h.invoke(type, data) }
        cachedEvents.clear()
      }
    }
  }

  fun emitEvent(type: CallEventType, data: JSONObject) {
    Log.d(TAG, "Emitting event: ${type.name}") // Use type.name for string value
    val dataString = data.toString()
    if (eventHandler != null) {
      eventHandler?.invoke(type, dataString)
    } else {
      Log.d(TAG, "No event handler, caching event: ${type.name}") // Use type.name
      cachedEvents.add(Pair(type, dataString))
    }
  }

  // NEW: Emit CALL_STATE_CHANGED event
  private fun emitCallStateChanged() {
    val currentIsActive = CallEngine.isCallActive()
    if (currentIsActive != previousCallStateActive) {
        Log.d(TAG, "CALL_STATE_CHANGED: isActive=$currentIsActive")
        emitEvent(CallEventType.CALL_STATE_CHANGED, JSONObject().apply {
            put("isActive", currentIsActive)
        })
        previousCallStateActive = currentIsActive
    }
  }

  private fun supportsCallStyleNotifications(): Boolean {
    // CallStyle notifications are available from Android S (API 31)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()

    val supportedManufacturers = setOf(
      "google", "samsung", "oneplus", "motorola", "sony", "lg", "htc"
    )

    val supportedBrands = setOf(
      "google", "samsung", "oneplus", "motorola", "sony", "lg", "htc", "pixel"
    )

    val isSupported = supportedManufacturers.contains(manufacturer) ||
                     supportedBrands.contains(brand) ||
                     manufacturer.contains("google") ||
                     brand.contains("pixel")

    Log.d(TAG, "CallStyle support check - Manufacturer: $manufacturer, Brand: $brand, Supported: $isSupported")
    return isSupported
  }

  fun silenceIncomingCall() {
    Log.d(TAG, "Silencing incoming call ringtone via Connection.onSilence()")
    stopRingtone()
  }

  fun registerLockScreenBypassCallback(callback: LockScreenBypassCallback) {
    lockScreenBypassCallbacks.add(callback)
  }

  fun unregisterLockScreenBypassCallback(callback: LockScreenBypassCallback) {
    lockScreenBypassCallbacks.remove(callback)
  }

  private fun updateLockScreenBypass() {
    val shouldBypass = CallEngine.isCallActive()
    if (lockScreenBypassActive != shouldBypass) {
      lockScreenBypassActive = shouldBypass
      Log.d(TAG, "Lock screen bypass state changed: $lockScreenBypassActive")
      lockScreenBypassCallbacks.forEach { callback ->
        try {
          callback.onLockScreenBypassChanged(shouldBypass)
        } catch (e: Exception) {
          Log.w(TAG, "Error notifying lock screen bypass callback", e)
        }
      }
    }
  }

  fun isLockScreenBypassActive(): Boolean = lockScreenBypassActive

  fun addTelecomConnection(callId: String, connection: Connection) {
    telecomConnections[callId] = connection
    Log.d(TAG, "Added Telecom Connection for callId: $callId")
  }

  fun removeTelecomConnection(callId: String) {
    telecomConnections.remove(callId)
    callAnswerStates.remove(callId) // Clean up answer state
    Log.d(TAG, "Removed Telecom Connection for callId: $callId")
  }

  fun getTelecomConnection(callId: String): Connection? = telecomConnections[callId]

  fun getCallAnswerState(callId: String): Boolean? = callAnswerStates.remove(callId)

  fun setCanMakeMultipleCalls(allow: Boolean) {
    canMakeMultipleCalls = allow
    Log.d(TAG, "canMakeMultipleCalls set to: $allow")
  }

  fun getCurrentCallState(): String {
    val calls = getActiveCalls()
    val jsonArray = JSONArray()
    calls.forEach {
      jsonArray.put(it.toJsonObject())
    }
    return jsonArray.toString()
  }

  fun reportIncomingCall(
    context: Context,
    callId: String,
    callType: String,
    displayName: String,
    pictureUrl: String? = null,
    metadata: String? = null,
    token: String? = null,
    rejectEndpoint: String? = null
  ) {
    if (!isInitialized.get()) {
      initialize(context)
    }

    Log.d(TAG, "reportIncomingCall: callId=$callId, type=$callType, name=$displayName")
    metadata?.let { callMetadata[callId] = it }

    token?.let { callTokens[callId] = it }
    rejectEndpoint?.let { callRejectEndpoints[callId] = it }

    val incomingCall = activeCalls.values.find { it.state == CallState.INCOMING }
    if (incomingCall != null) {
      Log.d(TAG, "Incoming call collision detected. Auto-rejecting new call: $callId")
      rejectIncomingCallCollision(callId, "Another call is already incoming")
      return
    }

    val activeOrHeldCall = activeCalls.values.find {
      it.state == CallState.ACTIVE || it.state == CallState.HELD
    }
    if (activeOrHeldCall != null && !canMakeMultipleCalls) {
      Log.d(TAG, "Active/Held call exists when receiving incoming call. Auto-rejecting: $callId")
      rejectIncomingCallCollision(callId, "Another call is already active or held")
      return
    }

    val isVideoCall = callType == "Video"
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
      activeCalls.values.forEach {
        if (it.state == CallState.ACTIVE) {
          holdCallInternal(it.callId, heldBySystem = false)
        }
      }
    }

    activeCalls[callId] =
      CallInfo(callId, callType, displayName, pictureUrl, CallState.INCOMING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. State: INCOMING")

    startForegroundService()
    updateOverallIdleTimerDisabledState()
    emitCallStateChanged()

    showIncomingCallUI(callId, displayName, callType, pictureUrl)
    registerPhoneAccount()

    val telecomManager =
      requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()
    val extras = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, displayName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      pictureUrl?.let { putString(MyConnectionService.EXTRA_PICTURE_URL, it) }
      putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    try {
      telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
      Log.d(TAG, "Successfully reported incoming call to TelecomManager for $callId")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to report incoming call: ${e.message}", e)
      endCallInternal(callId)
    }

    updateLockScreenBypass()
  }

  fun startOutgoingCall(
    callId: String,
    callType: String,
    targetName: String,
    metadata: String? = null
  ) {
    val context = requireContext()
    Log.d(TAG, "startOutgoingCall: callId=$callId, type=$callType, target=$targetName")
    metadata?.let { callMetadata[callId] = it }

    if (!validateOutgoingCallRequest()) {
      Log.w(TAG, "Rejecting outgoing call - incoming/active call exists")
      emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
        put("callId", callId)
        put("reason", "Cannot start outgoing call while incoming or active call exists")
      })
      return
    }

    val isVideoCall = callType == "Video"
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
      activeCalls.values.forEach {
        if (it.state == CallState.ACTIVE) {
          holdCallInternal(it.callId, heldBySystem = false)
        }
      }
    }

    activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.DIALING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. State: DIALING")

    startForegroundService() // Ensure foreground service is started for outgoing
    updateOverallIdleTimerDisabledState() // NEW: Update screen awake state
    emitCallStateChanged() // NEW: Emit call state changed event

    registerPhoneAccount()

    val telecomManager =
      context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = CallEngine.getPhoneAccountHandle()
    val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, targetName, null)

    val outgoingExtrasForConnectionService = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, targetName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      metadata?.let { putString("metadata", it) }
    }

    val placeCallExtras = Bundle().apply {
      putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
      putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtrasForConnectionService)
      // Hint to Telecom whether to start with speakerphone based on video call type
      putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCall)
      putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    try {
      telecomManager.placeCall(addressUri, placeCallExtras)
      startRingback()
      bringAppToForeground()
      Log.d(TAG, "Successfully reported outgoing call to TelecomManager")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start outgoing call: ${e.message}", e)
      endCallInternal(callId)
    }

    updateLockScreenBypass()
  }

  fun startCall(
    callId: String,
    callType: String,
    targetName: String,
    metadata: String? = null
  ) {
    Log.d(TAG, "startCall: callId=$callId, type=$callType, target=$targetName")
    metadata?.let { callMetadata[callId] = it }

    val existingCallInfo = activeCalls[callId]
    if (existingCallInfo != null && existingCallInfo.state == CallState.INCOMING) {
      // Scenario 1: Call with this ID is already incoming, answer it.
      Log.d(TAG, "Call $callId is incoming, answering it directly via startCall.")
      answerCall(callId, isLocalAnswer = false) // Remote party answered, so not local UI initiated
      return
    }

    // Scenario 2: Call is new or not incoming, treat as new outgoing call that is immediately active.
    Log.d(TAG, "Call $callId is new or not incoming. Initiating as outgoing and immediately active.")
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
        if (!validateOutgoingCallRequest()) {
            Log.w(TAG, "Rejecting startCall as outgoing - incoming/active call exists")
            emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
                put("callId", callId)
                put("reason", "Cannot start new active call while incoming or active call exists")
            })
            return
        }
    }

    val isVideoCall = callType == "Video"
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
      activeCalls.values.forEach {
        if (it.state == CallState.ACTIVE) {
          holdCallInternal(it.callId, heldBySystem = false)
        }
      }
    }

    activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.DIALING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. Initial state: DIALING (for Telecom)")

    startForegroundService()
    updateOverallIdleTimerDisabledState()
    emitCallStateChanged()

    registerPhoneAccount()

    val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()
    val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, targetName, null)

    val outgoingExtrasForConnectionService = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, targetName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      metadata?.let { putString("metadata", it) }
    }

    val placeCallExtras = Bundle().apply {
      putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
      putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtrasForConnectionService)
      putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCall)
      putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    try {
      telecomManager.placeCall(addressUri, placeCallExtras)
      bringAppToForeground()
      Log.d(TAG, "Successfully reported outgoing call (to be immediately active) to TelecomManager for $callId")

      answerCall(callId, isLocalAnswer = false)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start call as active: ${e.message}", e)
      endCallInternal(callId)
    }
    updateLockScreenBypass()
  }

  // NEW UNIFIED ANSWER METHOD
  fun answerCall(callId: String, isLocalAnswer: Boolean = true) {
    Log.d(TAG, "answerCall: $callId, isLocalAnswer: $isLocalAnswer")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot answer call $callId - not found in active calls")
      return
    }

    // Store the isLocalAnswer state for the connection to use
    callAnswerStates[callId] = isLocalAnswer

    // Always call connection.onAnswer() to let Telecom handle the flow
    telecomConnections[callId]?.let { connection ->
      connection.onAnswer() // This will trigger MyConnection.onAnswer()
    } ?: run {
      Log.w(TAG, "No telecom connection found for $callId, falling back to direct answer")
      coreCallAnswered(callId, isLocalAnswer)
    }
  }

  // INTERNAL METHOD called by MyConnection.onAnswer()
  internal fun coreCallAnswered(callId: String, isLocalAnswer: Boolean) {
    Log.d(TAG, "coreCallAnswered: $callId, isLocalAnswer: $isLocalAnswer")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot answer call $callId - not found in active calls")
      return
    }

    activeCalls[callId] = callInfo.copy(state = CallState.ACTIVE)
    currentCallId = callId
    callStartTime = System.currentTimeMillis()
    wasManuallySetAudioRoute = false
    Log.d(TAG, "Call $callId set to ACTIVE state")

    stopRingtone()
    stopRingback()
    cancelIncomingCallUI()

    if (!canMakeMultipleCalls) {
      activeCalls.filter { it.key != callId }.values.forEach { otherCall ->
        if (otherCall.state == CallState.ACTIVE) {
          holdCallInternal(otherCall.callId, heldBySystem = false)
        }
      }
    }

    startForegroundService() // Update foreground service status
    bringAppToForeground() // Always try to bring app to foreground when answering
    updateOverallIdleTimerDisabledState() // NEW: Update screen awake state
    emitCallStateChanged() // NEW: Emit call state changed event

    setAudioMode()

    // Set initial audio route using appropriate API
    setInitialCallAudioRoute(callId, callInfo.callType)

    if (isLocalAnswer) {
      emitCallAnsweredWithMetadata(callId)
    } else {
      emitOutgoingCallAnsweredWithMetadata(callId)
    }

    Log.d(TAG, "Call $callId successfully answered")
  }

  private fun emitCallAnsweredWithMetadata(callId: String) {
    val callInfo = activeCalls[callId] ?: return
    val metadata = callMetadata[callId]

    emitEvent(CallEventType.CALL_ANSWERED, JSONObject().apply {
      put("callId", callId)
      put("callType", callInfo.callType)
      put("displayName", callInfo.displayName)
      callInfo.pictureUrl?.let { put("pictureUrl", it) }
      metadata?.let {
        try {
          put("metadata", JSONObject(it))
        } catch (e: Exception) {
          put("metadata", it)
        }
      }
    })
  }

  private fun emitOutgoingCallAnsweredWithMetadata(callId: String) {
    val callInfo = activeCalls[callId] ?: return
    val metadata = callMetadata[callId]

    emitEvent(CallEventType.OUTGOING_CALL_ANSWERED, JSONObject().apply {
      put("callId", callId)
      put("callType", callInfo.callType)
      put("displayName", callInfo.displayName)
      callInfo.pictureUrl?.let { put("pictureUrl", it) }
      metadata?.let {
        try {
          put("metadata", JSONObject(it))
        } catch (e: Exception) {
          put("metadata", it)
        }
      }
    })
  }

  fun holdCall(callId: String) {
    holdCallInternal(callId, heldBySystem = false)
  }

  fun setOnHold(callId: String, onHold: Boolean) {
    Log.d(TAG, "setOnHold: $callId, onHold: $onHold")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot set hold state for call $callId - not found")
      return
    }

    if (onHold && callInfo.state == CallState.ACTIVE) {
      holdCallInternal(callId, heldBySystem = false)
    } else if (!onHold && callInfo.state == CallState.HELD) {
      unholdCallInternal(callId, resumedBySystem = false)
    }
  }

  private fun holdCallInternal(callId: String, heldBySystem: Boolean) {
    Log.d(TAG, "holdCallInternal: $callId, heldBySystem: $heldBySystem")
    val callInfo = activeCalls[callId]
    if (callInfo?.state != CallState.ACTIVE) {
      Log.w(TAG, "Cannot hold call $callId - not in active state")
      return
    }

    activeCalls[callId] = callInfo.copy(
      state = CallState.HELD,
      wasHeldBySystem = heldBySystem
    )

    telecomConnections[callId]?.setOnHold()
    updateForegroundNotification()
    emitEvent(CallEventType.CALL_HELD, JSONObject().put("callId", callId))
    updateLockScreenBypass()
    emitCallStateChanged()
  }

  fun unholdCall(callId: String) {
    unholdCallInternal(callId, resumedBySystem = false)
  }

  private fun unholdCallInternal(callId: String, resumedBySystem: Boolean) {
    Log.d(TAG, "unholdCallInternal: $callId, resumedBySystem: $resumedBySystem")
    val callInfo = activeCalls[callId]
    if (callInfo?.state != CallState.HELD) {
      Log.w(TAG, "Cannot unhold call $callId - not in held state")
      return
    }

    activeCalls[callId] = callInfo.copy(
      state = CallState.ACTIVE,
      wasHeldBySystem = false
    )

    telecomConnections[callId]?.setActive()
    updateForegroundNotification()
    emitEvent(CallEventType.CALL_UNHELD, JSONObject().put("callId", callId))
    updateLockScreenBypass()
    emitCallStateChanged()
  }

  fun muteCall(callId: String) {
    setMutedInternal(callId, true)
  }

  fun unmuteCall(callId: String) {
    setMutedInternal(callId, false)
  }

  fun setMuted(callId: String, muted: Boolean) {
    setMutedInternal(callId, muted)
  }

  private fun setMutedInternal(callId: String, muted: Boolean) {
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot set mute state for call $callId - not found")
      return
    }

    val context = requireContext()
    audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val wasMuted = audioManager?.isMicrophoneMute ?: false
    audioManager?.isMicrophoneMute = muted
    Log.d(TAG, "AudioManager microphone mute set to: $muted")

    if (wasMuted != muted) {
      val eventType = if (muted) CallEventType.CALL_MUTED else CallEventType.CALL_UNMUTED
      emitEvent(eventType, JSONObject().put("callId", callId))
      Log.d(TAG, "Call $callId mute state changed to: $muted")
    }
  }

  fun endCall(callId: String) {
    Log.d(TAG, "endCall: $callId")
    endCallInternal(callId)
  }

  fun endAllCalls() {
    Log.d(TAG, "endAllCalls: Ending all active calls")
    if (activeCalls.isEmpty()) return

    activeCalls.keys.toList().forEach { callId ->
      endCallInternal(callId)
    }

    if (activeCalls.isEmpty()) {
      cleanup()
    }
    updateLockScreenBypass()
    emitCallStateChanged()
  }

  private fun endCallInternal(callId: String) {
    Log.d(TAG, "endCallInternal: $callId")

    val callInfo = activeCalls[callId] ?: run {
      Log.w(TAG, "Call $callId not found in active calls")
      return
    }

    // Send rejection request if this was an incoming call being rejected
    if (callInfo.state == CallState.INCOMING) {
      sendCallRejectionRequest(callId)
    }

    val metadata = callMetadata.remove(callId)
    activeCalls.remove(callId)
    callAnswerStates.remove(callId)

    // Clean up token and endpoint
    callTokens.remove(callId)
    callRejectEndpoints.remove(callId)

    stopRingback()
    stopRingtone()
    cancelIncomingCallUI()

    if (currentCallId == callId) {
      currentCallId =
        activeCalls.filter { it.value.state != CallState.ENDED }.keys.firstOrNull()
    }

    val context = requireContext()
    val closeActivityIntent = Intent("com.qusaieilouti99.callmanager.CLOSE_CALL_ACTIVITY")
      .setPackage(context.packageName)
      .putExtra("callId", callId)

    try {
      context.sendBroadcast(closeActivityIntent)
      Log.d(TAG, "Sent close broadcast for CallActivity: $callId")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to send close broadcast: ${e.message}")
    }

    telecomConnections[callId]?.let { connection ->
      connection.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
      connection.destroy()
      removeTelecomConnection(callId)
    }

    if (activeCalls.isEmpty()) {
      cleanup() // Full cleanup if no more calls
    } else {
      updateForegroundNotification() // Update notification for remaining calls
    }

    updateLockScreenBypass()
    emitCallStateChanged() // NEW: Emit call state changed event

    for (listener in callEndListeners) {
      mainHandler.post {
        try {
          listener.onCallEnded(callId)
        } catch (_: Throwable) {
          // swallow
        }
      }
    }

    emitEvent(CallEventType.CALL_ENDED, JSONObject().apply {
      put("callId", callId)
      metadata?.let {
        try { put("metadata", JSONObject(it))
        } catch (e: Exception) { put("metadata", it) }
      }
    })
  }

  // ====== IMPROVED AUDIO ROUTING SYSTEM (supports both modern and legacy) ======

  // Modern CallEndpoint API methods (API 34+)
  @Suppress("NewApi")
  fun onTelecomAvailableEndpointsChanged(endpoints: List<android.telecom.CallEndpoint>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      @Suppress("UNCHECKED_CAST")
      availableCallEndpoints = endpoints as? List<Any> ?: emptyList() // Store as Any for compatibility
      Log.d(TAG, "Available CallEndpoints updated: ${endpoints.map { "${it.endpointName}(${CallEngine.mapCallEndpointTypeToString(it.endpointType)})" }}")
      emitAudioDevicesChanged()
    }
  }

  @Suppress("NewApi")
  fun onTelecomAudioRouteChanged(callId: String, callEndpoint: android.telecom.CallEndpoint) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      Log.d(TAG, "Telecom audio route changed for $callId: endpoint=${callEndpoint.endpointName} (type=${mapCallEndpointTypeToString(callEndpoint.endpointType)})")
      currentActiveCallEndpoint = callEndpoint
      emitAudioRouteChanged(mapCallEndpointTypeToString(callEndpoint.endpointType))
    }
  }

  fun getAudioDevices(): AudioRoutesInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      getModernAudioDevices()
    } else {
      getLegacyAudioDevices()
    }
  }

  @Suppress("NewApi")
  private fun getModernAudioDevices(): AudioRoutesInfo {
    @Suppress("UNCHECKED_CAST")
    val endpoints = availableCallEndpoints as? List<android.telecom.CallEndpoint> ?: emptyList()
    val devices = endpoints.map { StringHolder(mapCallEndpointTypeToString(it.endpointType)) }.toMutableSet()

    if (endpoints.none { it.endpointType == android.telecom.CallEndpoint.TYPE_EARPIECE }) {
        devices.add(StringHolder("Earpiece"))
    }
    if (endpoints.none { it.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER }) {
        devices.add(StringHolder("Speaker"))
    }

    val currentEndpoint = currentActiveCallEndpoint as? android.telecom.CallEndpoint
    val current = currentEndpoint?.let { mapCallEndpointTypeToString(it.endpointType) } ?: "Unknown"

    Log.d(TAG, "Modern audio devices: ${devices.map { it.value }}, current: $current")
    return AudioRoutesInfo(devices.toTypedArray(), current)
  }

  private fun getLegacyAudioDevices(): AudioRoutesInfo {
    updateLegacyAvailableAudioDevices()
    val devices = legacyAvailableAudioDevices.map { StringHolder(it) }.toSet()
    Log.d(TAG, "Legacy audio devices: ${devices.map { it.value }}, current: $legacyCurrentAudioRoute")
    return AudioRoutesInfo(devices.toTypedArray(), legacyCurrentAudioRoute)
  }

  fun setAudioRoute(route: String) {
    Log.d(TAG, "setAudioRoute called: $route (manual)")
    wasManuallySetAudioRoute = true

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      setModernAudioRoute(route)
    } else {
      setLegacyAudioRouteDirect(route)
    }
  }

  @Suppress("NewApi")
  private fun setModernAudioRoute(route: String) {
    val telecomEndpointType = mapStringToCallEndpointType(route)
    @Suppress("UNCHECKED_CAST")
    val endpoints = availableCallEndpoints as? List<android.telecom.CallEndpoint> ?: emptyList()

    val targetEndpoint = endpoints.find { it.endpointType == telecomEndpointType }
        ?: getOrCreateGenericCallEndpoint(telecomEndpointType, route)

    if (targetEndpoint != null) {
      currentCallId?.let { callId ->
        telecomConnections[callId]?.let { connection ->
          if (connection is MyConnection) {
            Log.d(TAG, "Requesting modern telecom audio route to: ${targetEndpoint.endpointName} (type: ${mapCallEndpointTypeToString(targetEndpoint.endpointType)})")
            connection.setTelecomAudioRoute(targetEndpoint)
          } else {
            Log.w(TAG, "Telecom connection for $callId is not MyConnection instance. Cannot set modern audio route.")
          }
        } ?: Log.w(TAG, "No telecom connection found for $callId to set audio route.")
      } ?: Log.w(TAG, "No current call ID to set audio route.")
    } else {
      Log.w(TAG, "Could not find or create a valid CallEndpoint for modern route: $route (type: $telecomEndpointType)")
    }
  }

  fun setLegacyAudioRouteDirect(route: String) {
    Log.d(TAG, "Setting legacy audio route: $route")

    val am = audioManager ?: return

    try {
      when (route) {
        "Speaker" -> {
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = true
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && @Suppress("DEPRECATION") am.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
          }
          legacyCurrentAudioRoute = "Speaker"
        }
        "Earpiece" -> {
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && @Suppress("DEPRECATION") am.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
          }
          legacyCurrentAudioRoute = "Earpiece"
        }
        "Bluetooth" -> {
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = false
          if (isBluetoothDeviceConnected()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              @Suppress("DEPRECATION")
              am.startBluetoothSco()
            }
            legacyCurrentAudioRoute = "Bluetooth"
          } else {
            Log.w(TAG, "No Bluetooth device connected, falling back to Earpiece")
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            legacyCurrentAudioRoute = "Earpiece"
          }
        }
        "Headset" -> {
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && @Suppress("DEPRECATION") am.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
          }
          if (isWiredHeadsetConnected()) {
            legacyCurrentAudioRoute = "Headset"
          } else {
            Log.w(TAG, "No wired headset connected, falling back to Earpiece")
            legacyCurrentAudioRoute = "Earpiece"
          }
        }
        else -> {
          Log.w(TAG, "Unknown legacy audio route: $route, falling back to Earpiece")
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = false
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && @Suppress("DEPRECATION") am.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
          }
          legacyCurrentAudioRoute = "Earpiece"
        }
      }

      Log.d(TAG, "Legacy audio route set to: $legacyCurrentAudioRoute")
      emitAudioRouteChanged(legacyCurrentAudioRoute)

    } catch (e: Exception) {
      Log.e(TAG, "Error setting legacy audio route: ${e.message}", e)
    }
  }

  fun setLegacyAudioRoute(endpoint: String) {
      setLegacyAudioRouteDirect(endpoint)
  }

  fun setInitialCallAudioRoute(callId: String, callType: String) {
    Log.d(TAG, "Setting initial audio route for callId: $callId, type: $callType")

    if (wasManuallySetAudioRoute) {
      Log.d(TAG, "Audio route was manually set, skipping initial route setting.")
      return
    }

    val targetRoute = when {
      isBluetoothDeviceConnected() -> "Bluetooth"
      isWiredHeadsetConnected() -> "Headset"
      callType.equals("Video", ignoreCase = true) -> "Speaker"
      else -> "Earpiece"
    }

    mainHandler.postDelayed({
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        setModernAudioRoute(targetRoute)
      } else {
        setLegacyAudioRouteDirect(targetRoute)
      }
    }, 500L)
  }

  private fun setAudioMode() {
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION")
  }

  private fun resetAudioMode() {
    if (activeCalls.isEmpty()) {
      audioManager?.let { am ->
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && @Suppress("DEPRECATION") am.isBluetoothScoOn) {
          @Suppress("DEPRECATION")
          am.stopBluetoothSco()
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
      }

      // Reset both modern and legacy state
      currentActiveCallEndpoint = null
      availableCallEndpoints = emptyList()
      legacyCurrentAudioRoute = "Earpiece"
      legacyAvailableAudioDevices = setOf("Earpiece", "Speaker")
      wasManuallySetAudioRoute = false

      Log.d(TAG, "Audio mode reset to MODE_NORMAL, audio endpoints reset.")
    }
  }

  @Suppress("NewApi")
  fun mapCallEndpointTypeToString(type: Int): String { // Made public
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      when (type) {
        android.telecom.CallEndpoint.TYPE_EARPIECE -> "Earpiece"
        android.telecom.CallEndpoint.TYPE_SPEAKER -> "Speaker"
        android.telecom.CallEndpoint.TYPE_BLUETOOTH -> "Bluetooth"
        android.telecom.CallEndpoint.TYPE_WIRED_HEADSET -> "Headset"
        android.telecom.CallEndpoint.TYPE_STREAMING -> "Streaming"
        else -> "Unknown"
      }
    } else {
      // Fallback for API < 34. These types technically don't exist on older APIs,
      // but the `Int` value can be passed. Return "Unknown" as a safe default
      // if this is ever called with an integer that doesn't correspond to a known type on older APIs.
      // This function is still safe because the `when` block with API 34+ constants is inside the `if` check.
      "Unknown"
    }
  }

  @Suppress("NewApi")
  fun mapStringToCallEndpointType(typeString: String): Int { // Made public
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      when (typeString) {
        "Earpiece" -> android.telecom.CallEndpoint.TYPE_EARPIECE
        "Speaker" -> android.telecom.CallEndpoint.TYPE_SPEAKER
        "Bluetooth" -> android.telecom.CallEndpoint.TYPE_BLUETOOTH
        "Headset" -> android.telecom.CallEndpoint.TYPE_WIRED_HEADSET
        "Streaming" -> android.telecom.CallEndpoint.TYPE_STREAMING
        else -> android.telecom.CallEndpoint.TYPE_UNKNOWN
      }
    } else {
      0 // Fallback value for API < 34. Return a generic or `TYPE_UNKNOWN` (which is 0).
    }
  }

  @Suppress("NewApi")
  fun getOrCreateGenericCallEndpoint(type: Int, name: String): android.telecom.CallEndpoint? { // Made public
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      when (type) {
        android.telecom.CallEndpoint.TYPE_EARPIECE -> android.telecom.CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Earpiece_Default".toByteArray())))
        android.telecom.CallEndpoint.TYPE_SPEAKER -> android.telecom.CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Speaker_Default".toByteArray())))
        android.telecom.CallEndpoint.TYPE_BLUETOOTH -> android.telecom.CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Bluetooth_Default".toByteArray())))
        android.telecom.CallEndpoint.TYPE_WIRED_HEADSET -> android.telecom.CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Headset_Default".toByteArray())))
        else -> null
      }
    } else {
      null // Return null for API < 34 as CallEndpoint doesn't exist
    }
  }

  private fun updateLegacyAvailableAudioDevices() {
    val devices = mutableSetOf<String>()

    devices.add("Earpiece")
    devices.add("Speaker")

    // Check for Bluetooth
    if (isBluetoothDeviceConnected()) {
      devices.add("Bluetooth")
    }

    // Check for wired headset
    if (isWiredHeadsetConnected()) {
      devices.add("Headset")
    }

    legacyAvailableAudioDevices = devices
  }

  private fun isWiredHeadsetConnected(): Boolean {
    val am = audioManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      devices.any { device ->
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
      }
    } else {
        @Suppress("DEPRECATION")
        am.isWiredHeadsetOn
    }
  }

  private fun isBluetoothDeviceConnected(): Boolean {
    val am = audioManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      devices.any { device ->
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
      }
    } else {
        @Suppress("DEPRECATION")
        am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }
  }

  private fun emitAudioRouteChanged(currentRoute: String) {
    val info = getAudioDevices()
    val deviceStrings = info.devices.map { it.value }
    val payload = JSONObject().apply {
      put("devices", JSONArray(deviceStrings))
      put("currentRoute", currentRoute)
    }
    emitEvent(CallEventType.AUDIO_ROUTE_CHANGED, payload)
    Log.d(TAG, "Audio route changed: $currentRoute, available: $deviceStrings")
  }

  private fun emitAudioDevicesChanged() {
    val info = getAudioDevices()
    val deviceStrings = info.devices.map { it.value }
    val payload = JSONObject().apply {
      put("devices", JSONArray(deviceStrings))
      put("currentRoute", info.currentRoute)
    }
    emitEvent(CallEventType.AUDIO_DEVICES_CHANGED, payload)
    Log.d(TAG, "Audio devices changed: available: $deviceStrings")
  }

  // ====== END IMPROVED AUDIO ROUTING SYSTEM ======

  // NEW: Manual control for idle timer disabled
  fun setIdleTimerDisabled(shouldDisable: Boolean) {
      Log.d(TAG, "setIdleTimerDisabled (JS requested): $shouldDisable")
      manualIdleTimerDisabled = shouldDisable
      updateOverallIdleTimerDisabledState()
  }

  // NEW: Internal method to determine and set final idle timer state
  private fun updateOverallIdleTimerDisabledState() {
      // Screen should stay awake if:
      // 1. Manually requested (e.g., from JS)
      // 2. Any call is active (INCOMING, DIALING, ACTIVE, HELD)
      // 3. Any activity of the app is currently visible (to ensure screen stays on while using the app even if no call is active)
      val shouldDisable = manualIdleTimerDisabled || CallEngine.isCallActive() || isAppCurrentlyVisible
      mainHandler.post {
          appContext?.let {
              if (it is Activity) {
                  // For an Activity context, use FLAG_KEEP_SCREEN_ON
                  if (shouldDisable) {
                      it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                  } else {
                      it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                  }
                  Log.d(TAG, "Activity screen awake state updated. FLAG_KEEP_SCREEN_ON = $shouldDisable (manual=${CallEngine.manualIdleTimerDisabled}, hasCalls=${CallEngine.isCallActive()}, appVisible=${isAppCurrentlyVisible})")
              } else {
                  // For application context, PowerManager.WakeLock is appropriate for keeping CPU/screen on.
                  val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
                  if (shouldDisable) {
                      if (wakeLock == null || wakeLock?.isHeld == false) {
                          // Use SCREEN_DIM_WAKE_LOCK to keep the screen on but dim.
                          // ACQUIRE_CAUSES_WAKEUP is important to turn the screen on if it's off.
                          @Suppress("DEPRECATION")
                          wakeLock = powerManager.newWakeLock(
                              PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                              "CallEngine:ScreenWakeLock"
                          )
                          // Acquire with a reasonable timeout. The foreground service ensures re-acquisition if needed.
                          wakeLock?.acquire(60 * 60 * 1000L /* 1 hour */)
                          Log.d(TAG, "Acquired SCREEN_DIM_WAKE_LOCK (from Application context)")
                      }
                  } else {
                      wakeLock?.let { wl ->
                          if (wl.isHeld) {
                              wl.release()
                              Log.d(TAG, "Released SCREEN_DIM_WAKE_LOCK (from Application context)")
                          }
                      }
                      wakeLock = null
                  }
                  Log.d(TAG, "Application context screen awake state updated. WakeLock status: ${wakeLock?.isHeld ?: false} (manual=${CallEngine.manualIdleTimerDisabled}, hasCalls=${CallEngine.isCallActive()}, appVisible=${isAppCurrentlyVisible})")
              }
          } ?: Log.e(TAG, "Cannot update screen awake state, appContext is null.")
      }
  }


  // Replaced original `keepScreenAwake` with the internal one. The CallManager will now call `setIdleTimerDisabled`.
  fun keepScreenAwake(keepAwake: Boolean) {
      // This function is now superseded by setIdleTimerDisabled, which is called by CallManager.
      // Retaining it here for backward compatibility if directly called, but internal logic
      // is handled by updateOverallIdleTimerDisabledState based on manualIdleTimerDisabled and hasActiveCalls.
      Log.w(TAG, "DEPRECATED: CallEngine.keepScreenAwake() is deprecated. Use setIdleTimerDisabled() via CallManager instead.")
      setIdleTimerDisabled(keepAwake)
  }

  fun getActiveCalls(): List<CallInfo> = activeCalls.values.toList()
  fun isCallActive(): Boolean = activeCalls.any {
    it.value.state == CallState.ACTIVE ||
    it.value.state == CallState.INCOMING ||
    it.value.state == CallState.DIALING ||
    it.value.state == CallState.HELD
  }

  /**
   * Resolves the only trusted input that [CallActivity] may consume from an external launch:
   * a candidate call ID. All display data and lock-screen eligibility are derived from the
   * validated internal call state.
   */
  internal fun resolveIncomingCallUiState(callId: String?): IncomingCallUiState? {
    if (callId.isNullOrBlank()) {
      Log.w(TAG, "Rejecting CallActivity launch: missing callId")
      return null
    }

    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Rejecting CallActivity launch: callId=$callId not found in active call state")
      return null
    }

    if (callInfo.state != CallState.INCOMING) {
      Log.w(
        TAG,
        "Rejecting CallActivity launch: callId=$callId is not ringing (state=${callInfo.state})"
      )
      return null
    }

    return IncomingCallUiState(
      callId = callInfo.callId,
      callerName = callInfo.displayName,
      callType = callInfo.callType,
      callerAvatarUrl = callInfo.pictureUrl
    )
  }

  private fun validateOutgoingCallRequest(): Boolean {
    return !activeCalls.any {
      (!canMakeMultipleCalls && (it.value.state == CallState.INCOMING || it.value.state == CallState.ACTIVE))
    }
  }

  private fun rejectIncomingCallCollision(callId: String, reason: String) {
    emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
      put("callId", callId)
      put("reason", reason)
    })

    val existingCall = activeCalls[callId]
    if (existingCall == null) {
      callMetadata.remove(callId)
      Log.d(TAG, "Removed metadata for rejected call $callId (no existing call)")
    } else {
      Log.d(TAG, "Kept metadata for callId: $callId (existing call: ${existingCall.state})")
    }
  }

  private fun createNotificationChannel() {
    val context = requireContext()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        NOTIF_CHANNEL_ID,
        "Incoming Call Channel",
        NotificationManager.IMPORTANCE_HIGH
      )
      channel.description = "Notifications for incoming calls"
      channel.enableLights(true)
      channel.lightColor = Color.GREEN
      channel.enableVibration(true)
      channel.setBypassDnd(true)
      channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      channel.setSound(null, null)
      channel.importance = NotificationManager.IMPORTANCE_HIGH

      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  private fun showIncomingCallUI(callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    val context = requireContext()
    Log.d(TAG, "Showing incoming call UI for $callId")

    val useCallStyleNotification = supportsCallStyleNotifications()
    Log.d(TAG, "Using CallStyle notification: $useCallStyleNotification")

    val isDeviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.isKeyguardLocked
    } else {
        false // For API < 21, assume not locked or locking doesn't prevent activity launch
    }

    // Prioritize CallActivity overlay if permission is granted AND device is locked, OR if CallStyle is NOT supported.
    // Otherwise, default to standard notification.
    val hasOverlayPermission = CallEngine.checkOverlayPermissionGranted(context)

    if (hasOverlayPermission && isDeviceLocked) {
      Log.d(TAG, "Overlay permission granted and device is locked - attempting CallActivity overlay.")
      showCallActivityOverlay(context, callId)
    } else if (hasOverlayPermission && !useCallStyleNotification) {
      Log.d(TAG, "Overlay permission granted and CallStyle not supported - attempting CallActivity overlay.")
      showCallActivityOverlay(context, callId)
    }
    else {
      Log.d(TAG, "Defaulting to standard notification (e.g., unlocked and CallStyle supported, or no overlay permission).")
      showStandardNotification(context, callId, callerName, callType, callerPicUrl)
    }
    playRingtone()
  }

  private fun showCallActivityOverlay(context: Context, callId: String) {
    val overlayIntent = Intent(context, CallActivity::class.java).apply {
      addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_NO_ANIMATION or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
      )
      putExtra(CallActivity.EXTRA_CALL_ID, callId)
    }

    try {
      val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      @Suppress("DEPRECATION")
      val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "CallEngine:LockScreenWake"
      )
      wakeLock.acquire(5000) // Acquire for a short duration to ensure screen is on for activity launch
      context.startActivity(overlayIntent)
      Log.d(TAG, "Successfully launched CallActivity overlay")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch CallActivity overlay: ${e.message}. Falling back to standard notification.", e)
      val trustedCall = resolveIncomingCallUiState(callId)
      if (trustedCall != null) {
        showStandardNotification(
          context,
          trustedCall.callId,
          trustedCall.callerName,
          trustedCall.callType,
          trustedCall.callerAvatarUrl
        )
      } else {
        Log.w(
          TAG,
          "Skipping standard notification fallback because callId=$callId is no longer a trusted incoming call"
        )
      }
    }
  }

  private fun showStandardNotification(context: Context, callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    createNotificationChannel()
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra(CallActivity.EXTRA_CALL_ID, callId)
    }

    val fullScreenPendingIntent = PendingIntent.getActivity(
      context, callId.hashCode(), fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val answerIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
      action = "com.qusaieilouti99.callmanager.ANSWER_CALL"
      putExtra("callId", callId)
    }
    val answerPendingIntent = PendingIntent.getBroadcast(
      context, 0, answerIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val declineIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
      action = "com.qusaieilouti99.callmanager.DECLINE_CALL"
      putExtra("callId", callId)
    }
    val declinePendingIntent = PendingIntent.getBroadcast(
      context, 1, declineIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsCallStyleNotifications()) {
      val person = android.app.Person.Builder()
        .setName(callerName)
        .setImportant(true)
        .build()

      val builder = Notification.Builder(context, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setStyle(
          Notification.CallStyle.forIncomingCall(
            person,
            declinePendingIntent,
            answerPendingIntent
          )
        )
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .setOngoing(true)
        .setAutoCancel(false)
        .setCategory(Notification.CATEGORY_CALL)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setSound(null)

      // Apply deprecated methods with suppression
      @Suppress("DEPRECATION")
      builder.setPriority(Notification.PRIORITY_MAX)

      builder
    } else {
      val builder = Notification.Builder(context, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle("Incoming Call")
        .setContentText(callerName)
        .setCategory(Notification.CATEGORY_CALL)
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .setOngoing(true)
        .setAutoCancel(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))

      // Apply deprecated methods with suppression
      @Suppress("DEPRECATION")
      builder.setPriority(Notification.PRIORITY_MAX)

      @Suppress("DEPRECATION")
      builder.addAction(android.R.drawable.sym_action_call, "Answer", answerPendingIntent)

      @Suppress("DEPRECATION")
      builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)

      builder
    }

    val notification = notificationBuilder.build()
    notificationManager.notify(NOTIF_ID, notification)
  }

  fun cancelIncomingCallUI() {
    val context = requireContext()
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(NOTIF_ID)
    stopRingtone()
  }

  private fun startForegroundService() {
    val context = requireContext()
    val currentCall = activeCalls.values.find {
      it.state == CallState.ACTIVE ||
      it.state == CallState.INCOMING ||
      it.state == CallState.DIALING ||
      it.state == CallState.HELD
    }

    val intent = Intent(context, CallForegroundService::class.java)
    currentCall?.let {
      intent.putExtra("callId", it.callId)
      intent.putExtra("callType", it.callType)
      intent.putExtra("displayName", it.displayName)
      intent.putExtra("state", it.state.name)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
  }

  private fun stopForegroundService() {
    val context = requireContext()
    val intent = Intent(context, CallForegroundService::class.java)
    context.stopService(intent)
  }

  private fun updateForegroundNotification() {
    startForegroundService()
  }

  // Renamed to be explicit about what it checks
  private fun isMainActivityInForeground(): Boolean {
      return isMainActivityCurrentlyVisible
  }

  private fun bringAppToForeground() {
    // Only attempt to bring MainActivity to foreground if it's NOT already visible.
    if (isMainActivityInForeground()) {
        Log.d(TAG, "MainActivity is already in foreground, skipping launch.")
        return
    }

    Log.d(TAG, "Attempting to bring MainActivity to foreground.")
    val context = requireContext()
    val packageName = context.packageName
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    launchIntent?.addFlags(
      Intent.FLAG_ACTIVITY_NEW_TASK or
      Intent.FLAG_ACTIVITY_CLEAR_TOP or
      Intent.FLAG_ACTIVITY_SINGLE_TOP
    )

    if (isCallActive()) {
      launchIntent?.putExtra("BYPASS_LOCK_SCREEN", true)
    }

    try {
      context.startActivity(launchIntent)
      // Small delay to allow activity to start, then update bypass.
      mainHandler.postDelayed({
          updateLockScreenBypass()
      }, 100)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch MainActivity: ${e.message}")
    }
  }

  private fun registerPhoneAccount() {
    val context = requireContext()
    val telecomManager =
      context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()

    if (telecomManager.getPhoneAccount(phoneAccountHandle) == null) {
      val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "PingMe Call")
        .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
        .build()

      try {
        telecomManager.registerPhoneAccount(phoneAccount)
        Log.d(TAG, "PhoneAccount registered successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to register PhoneAccount: ${e.message}", e)
      }
    }
  }

  private fun getPhoneAccountHandle(): PhoneAccountHandle {
    val context = requireContext()
    return PhoneAccountHandle(
      ComponentName(context, MyConnectionService::class.java),
      PHONE_ACCOUNT_ID
    )
  }

  private fun playRingtone() {
    val context = requireContext()
    audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager?.mode = AudioManager.MODE_RINGTONE

    vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    vibrator?.let { v ->
      val pattern = longArrayOf(0L, 500L, 500L)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(pattern, 0)
      }
    }

    try {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      ringtone = RingtoneManager.getRingtone(context, uri)
      ringtone?.play()
      Log.d(TAG, "Ringtone started playing")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play ringtone", e)
    }
  }

  fun stopRingtone() {
    try {
      ringtone?.stop()
      Log.d(TAG, "Ringtone stopped")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping ringtone", e)
    }
    ringtone = null

    vibrator?.cancel()
    vibrator = null
  }

  private fun startRingback() {
    val context = requireContext()
    if (ringbackPlayer?.isPlaying == true) {
        Log.d(TAG, "Ringback already playing, skipping")
        return
    }

    try {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d(TAG, "✓ Audio mode set to MODE_IN_COMMUNICATION")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()

            val focusResult = audioManager?.requestAudioFocus(audioFocusRequest!!)
            Log.d(TAG, "✓ Audio focus result: $focusResult")
        }

        ringbackPlayer = MediaPlayer().apply {
            // ✅ CHANGED: Use direct resource reference
            val afd = context.resources.openRawResourceFd(R.raw.ringback_tone)
            if (afd != null) {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                Log.d(TAG, "✓ Loaded ringback from library resources")
            } else {
                Log.e(TAG, "❌ Could not open ringback_tone resource")
                return
            }

            setAudioStreamType(AudioManager.STREAM_VOICE_CALL)
            Log.d(TAG, "✓ Set audio stream to STREAM_VOICE_CALL")

            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            isLooping = true

            setOnPreparedListener { mp ->
                Log.d(TAG, "✓✓✓ RINGBACK PREPARED - STARTING PLAYBACK ✓✓✓")
                try {
                    mp.start()
                    Log.d(TAG, "✓ MediaPlayer.start() called successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error starting playback: ${e.message}", e)
                }
            }

            setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "❌ MediaPlayer ERROR: what=$what, extra=$extra")
                false
            }

            prepareAsync()
            Log.d(TAG, "✓ Called prepareAsync()")
        }

        Log.d(TAG, "✓✓✓ Ringback initialization complete ✓✓✓")
    } catch (e: Exception) {
        Log.e(TAG, "❌ CRITICAL ERROR in startRingback: ${e.message}", e)
        e.printStackTrace()
        ringbackPlayer = null
    }
  }

  private fun stopRingback() {
    try {
      ringbackPlayer?.stop()
      ringbackPlayer?.release()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping ringback: ${e.message}")
    } finally {
      ringbackPlayer = null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let {
        audioManager?.abandonAudioFocusRequest(it)
        audioFocusRequest = null
      }
    } else {
      @Suppress("DEPRECATION")
      audioManager?.abandonAudioFocus(null)
    }

    Log.d(TAG, "Ringback stopped and audio focus released")
  }

  private fun cleanup() {
    Log.d(TAG, "Performing cleanup")
    stopForegroundService()
    updateOverallIdleTimerDisabledState()
    resetAudioMode()
  }

  fun onApplicationTerminate() {
    Log.d(TAG, "Application terminating")
    // Unregister lifecycle callbacks first to prevent further state changes during termination
    (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)

    activeCalls.keys.toList().forEach { callId ->
      telecomConnections[callId]?.let { conn ->
        conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        conn.destroy()
      }
    }
    activeCalls.clear()
    telecomConnections.clear()
    callMetadata.clear()
    callAnswerStates.clear()
    currentCallId = null
    cleanup()
    lockScreenBypassCallbacks.clear()
    eventHandler = null
    cachedEvents.clear()
    isInitialized.set(false)
    appContext = null
    currentActiveCallEndpoint = null
    availableCallEndpoints = emptyList()
    wasManuallySetAudioRoute = false
    previousCallStateActive = false
    manualIdleTimerDisabled = false
    foregroundActivitiesCount = 0
    isAppCurrentlyVisible = false
    isMainActivityCurrentlyVisible = false
  }

  // --- Refactored SYSTEM_ALERT_WINDOW permission functions ---

  /**
   * Checks if the SYSTEM_ALERT_WINDOW permission (Draw Over Other Apps) is granted.
   * This function only checks; it does not launch any UI for permission request.
   * @param context The application context.
   * @return True if the permission is granted or not required (API < 23), false otherwise.
   */
  fun checkOverlayPermissionGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(context)
    } else {
      true // Permissions granted at install time for older Android versions
    }
  }

  /**
   * Sends HTTP POST request to reject endpoint when call is rejected
   */
  private fun sendCallRejectionRequest(callId: String) {
    val token = callTokens[callId]
    val endpoint = callRejectEndpoints[callId]

    if (token.isNullOrBlank() || endpoint.isNullOrBlank()) {
      Log.d(TAG, "No token or reject endpoint for callId: $callId, skipping rejection request")
      return
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val requestBody = JSONObject().apply {
          put("callId", callId)
        }

        val request = okhttp3.Request.Builder()
          .url(endpoint)
          .addHeader("Authorization", "Bearer $token")
          .addHeader("Content-Type", "application/json")
          .post(
            requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
          )
          .build()

        httpClient.newCall(request).execute().use { response ->
          if (response.isSuccessful) {
            Log.d(TAG, "Call rejection sent successfully for callId: $callId")
            response.body?.string()?.let { responseBody ->
              Log.d(TAG, "Response: $responseBody")
            }
          } else {
            Log.e(TAG, "Failed to send call rejection for callId: $callId, response: ${response.code}")
            response.body?.string()?.let { errorBody ->
              Log.e(TAG, "Error response: $errorBody")
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error sending call rejection request for callId: $callId", e)
      }
    }
  }

  /**
   * Launches the system settings screen where the user can grant the SYSTEM_ALERT_WINDOW permission.
   * This function should be called after your app has explained to the user why the permission is needed.
   * @param context The context to start the activity. Ideally, an Activity context is used,
   *                but if an Application context is used, FLAG_ACTIVITY_NEW_TASK will be added.
   * @return True if the settings activity was successfully launched, false otherwise.
   */
  fun launchOverlayPermissionSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      Log.d(TAG, "SYSTEM_ALERT_WINDOW permission automatically granted on API < 23. No settings to launch.")
      return true
    }

    Log.d(TAG, "Launching SYSTEM_ALERT_WINDOW permission settings.")
    try {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName)
      )
      if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.d(TAG, "Added FLAG_ACTIVITY_NEW_TASK as context is not an Activity.")
      }
      context.startActivity(intent)
      return true // Successfully launched settings
    } catch (e: Exception) {
      Log.e(TAG, "Failed to launch SYSTEM_ALERT_WINDOW permission settings: ${e.message}", e)
      return false // Failed to launch
    }
  }
}
