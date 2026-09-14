package net.matsudamper.browser.feature.addressautofill

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.address.AddressRepository
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.GeckoSession

enum class AddressAutofillFillMode {
    Address,
    Email,
}

/** 候補バーに出す文言。フォーカス中の欄の種類に合わせる。 */
enum class AddressAutofillSuggestionKind {
    Name,
    Address,
    Email,

    /** ページ固有のフォーム入力候補（住所等とは別経路） */
    FormField,
}

fun AddressAutofillSuggestionKind.toFillMode(): AddressAutofillFillMode {
    return when (this) {
        AddressAutofillSuggestionKind.Email -> AddressAutofillFillMode.Email

        AddressAutofillSuggestionKind.Name,
        AddressAutofillSuggestionKind.Address,
        AddressAutofillSuggestionKind.FormField,
        -> AddressAutofillFillMode.Address
    }
}

fun suggestionKindFromFieldKind(kind: String?): AddressAutofillSuggestionKind {
    return when (kind) {
        FIELD_KIND_EMAIL -> AddressAutofillSuggestionKind.Email
        FIELD_KIND_NAME -> AddressAutofillSuggestionKind.Name
        else -> AddressAutofillSuggestionKind.Address
    }
}

/**
 * Gecko FormAutofill はトップ文書から iframe を辿るため、shadow DOM 内の
 * cross-origin iframe（MDN の interactive-example など）では選択プロンプトを出せない。
 *
 * フォールバックは次の2経路:
 * 1. `AutocompleteStorageDelegate.onAddressFetch`（フォーカス時のフィールド検出で確実に来る）
 * 2. GeckoView Autofill の `onNodeFocus` / WebExtension の focusin
 *
 * 候補は IME 直上の独自バーで出す。Gboard などは displayCompletions を出さない。
 * 入力済みでも出し、選択で上書きする。住所の選択ではメールを埋めない。
 */
class AddressAutofillCoordinator(
    private val fillExtension: AddressAutofillWebExtension,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()

    /**
     * Custom Tab・ウェブアプリは通常ブラウザと同一プロセスで同時に生存する。
     * 接続を 1 つしか持たないと後から開いた画面が候補バーの宛先を奪い、
     * 先に開いていた画面では候補が出なくなるため、セッション単位で保持する。
     *
     * 住所取得はセッションを伴わない通知のため、末尾ほど新しい利用順で並べ、
     * 最後に接続またはフォーカスした画面へ向ける。
     */
    private val attachedSessions = LinkedHashMap<GeckoSession, Attached>()

    private class Attached(
        val session: GeckoSession,
        val host: AddressAutofillHost,
        val addressRepository: AddressRepository,
    ) {
        var showJob: Job? = null
        var hideJob: Job? = null
        var lastFieldKind: String? = null
        var suppressFocusUntilElapsed: Long = 0L
        var suppressFocusKind: String? = null

        fun cancelJobs() {
            showJob?.cancel()
            showJob = null
            hideJob?.cancel()
            hideJob = null
        }

        fun isFocusSuppressed(kind: String): Boolean {
            if (SystemClock.elapsedRealtime() >= suppressFocusUntilElapsed) return false
            return when (suppressFocusKind) {
                FIELD_KIND_EMAIL -> kind == FIELD_KIND_EMAIL
                else -> kind == FIELD_KIND_ADDRESS || kind == FIELD_KIND_NAME
            }
        }
    }

    fun attach(
        session: GeckoSession,
        host: AddressAutofillHost,
        addressRepository: AddressRepository,
    ) {
        val attached = synchronized(lock) {
            val previous = attachedSessions.remove(session)
            previous?.cancelJobs()
            Attached(session, host, addressRepository).also {
                it.lastFieldKind = previous?.lastFieldKind
                attachedSessions[session] = it
            }
        }
        host.focusedAutofillKind = attached.lastFieldKind
        host.onAddressSelectOptions = { options ->
            val kind = suggestionKindFromFieldKind(synchronized(lock) { attached.lastFieldKind })
            presentCompletions(attached, options.map { it.value }, kind)
        }
        fillExtension.registerSession(
            session,
            object : AddressAutofillWebExtension.SessionListener {
                override fun onFieldFocus(kind: String) {
                    this@AddressAutofillCoordinator.onFieldFocus(session, kind)
                }

                override fun onFieldBlur() {
                    this@AddressAutofillCoordinator.onFieldBlur(session)
                }

                override fun onFocusPortDisconnected() {
                    this@AddressAutofillCoordinator.onFocusPortDisconnected(session)
                }
            },
        )
    }

    fun detach(session: GeckoSession) {
        fillExtension.unregisterSession(session)
        val attached = synchronized(lock) {
            attachedSessions.remove(session)?.also { it.cancelJobs() }
        } ?: return
        attached.host.focusedAutofillKind = null
        attached.host.onAddressSelectOptions = null
        attached.host.hideAddressAutofillBar()
    }

    private fun fillSelectedAddress(
        attached: Attached,
        address: Autocomplete.Address,
        mode: AddressAutofillFillMode,
    ) {
        synchronized(lock) {
            if (attachedSessions[attached.session] !== attached) return
            attached.cancelJobs()
            attached.suppressFocusUntilElapsed =
                SystemClock.elapsedRealtime() + FILL_FOCUS_SUPPRESS_MS
            attached.suppressFocusKind = when (mode) {
                AddressAutofillFillMode.Email -> FIELD_KIND_EMAIL
                AddressAutofillFillMode.Address -> FIELD_KIND_ADDRESS
            }
        }
        attached.host.hideAddressAutofillBar()
        fillAddressOnSession(attached.session, address, mode)
        fillExtension.fill(attached.session, address, mode)
    }

    fun onAddressFetch(count: Int) {
        if (count <= 0) return
        val target = synchronized(lock) {
            val attached = attachedSessions.values.lastOrNull() ?: return
            // メール欄では住所候補を出さない。それ以外はフォーカス判定まで保留する。
            if (attached.lastFieldKind == FIELD_KIND_EMAIL) {
                Log.i(TAG, "onAddressFetch skipped: last field is email")
                return
            }
            if (attached.isFocusSuppressed(FIELD_KIND_ADDRESS)) {
                Log.i(TAG, "onAddressFetch ignored after address fill")
                return
            }
            attached
        }
        Log.i(TAG, "onAddressFetch schedule suggestion bar count=$count")
        val kind = suggestionKindFromFieldKind(synchronized(lock) { target.lastFieldKind })
        synchronized(lock) {
            target.showJob?.cancel()
            target.showJob = target.host.coroutineScope.launch {
                scheduleSuggestionBar(
                    addressRepository = target.addressRepository,
                    kind = kind,
                    shouldAbort = {
                        synchronized(lock) {
                            // 待機中にセッション付きのフォーカスが来ると宛先が変わる。
                            // 推測で選んだ宛先が末尾でなくなったら、その画面には出さない。
                            attachedSessions.values.lastOrNull() !== target ||
                                shouldAbortAddressFetchAfterFocusSettled(target) ||
                                target.isFocusSuppressed(FIELD_KIND_ADDRESS)
                        }
                    },
                    present = { addresses, suggestionKind ->
                        presentCompletions(target, addresses, suggestionKind)
                    },
                    ioDispatcher = ioDispatcher,
                )
            }
        }
    }

    fun onFieldFocus(session: GeckoSession, kind: String) {
        if (kind == FIELD_KIND_OTHER) {
            val attached = synchronized(lock) {
                val attached = attachedSessions[session] ?: return
                attached.host.autofillBarHideGeneration += 1
                attached.lastFieldKind = kind
                attached.cancelJobs()
                moveToMostRecent(session, attached)
                attached
            }
            attached.host.focusedAutofillKind = kind
            attached.host.hideAddressAutofillBar()
            Log.i(TAG, "field-focus kind=other")
            return
        }
        if (kind != FIELD_KIND_ADDRESS && kind != FIELD_KIND_NAME && kind != FIELD_KIND_EMAIL) return
        val attached = synchronized(lock) {
            val attached = attachedSessions[session] ?: return
            attached.host.autofillBarHideGeneration += 1
            if (attached.isFocusSuppressed(kind)) {
                Log.i(TAG, "field-focus ignored after fill kind=$kind")
                return
            }
            attached.lastFieldKind = kind
            attached.hideJob?.cancel()
            attached.hideJob = null
            moveToMostRecent(session, attached)
            attached
        }
        attached.host.focusedAutofillKind = kind
        Log.i(TAG, "field-focus kind=$kind")
        val suggestionKind = suggestionKindFromFieldKind(kind)
        synchronized(lock) {
            attached.showJob?.cancel()
            attached.showJob = attached.host.coroutineScope.launch {
                scheduleSuggestionBar(
                    addressRepository = attached.addressRepository,
                    kind = suggestionKind,
                    shouldAbort = {
                        synchronized(lock) {
                            attachedSessions[session] !== attached ||
                                attached.lastFieldKind != kind ||
                                attached.isFocusSuppressed(kind)
                        }
                    },
                    present = { addresses, presentKind ->
                        presentCompletions(attached, addresses, presentKind)
                    },
                    ioDispatcher = ioDispatcher,
                )
            }
        }
    }

    /**
     * 入力欄からフォーカスが外れたときに候補バーを閉じる。
     * バーをタップすると Gecko 側は先に blur するため、即消しせず短時間待ってから消す。
     * 待ち時間内に候補タップや再フォーカスがあれば hide を取り消す。
     */
    fun onFieldBlur(session: GeckoSession) {
        synchronized(lock) {
            val attached = attachedSessions[session] ?: return
            attached.showJob?.cancel()
            attached.showJob = null
            attached.lastFieldKind = FIELD_KIND_OTHER
            val hideGeneration = attached.host.autofillBarHideGeneration
            attached.hideJob?.cancel()
            attached.hideJob = attached.host.coroutineScope.launch {
                delay(ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS)
                val host = synchronized(lock) {
                    if (attachedSessions[session] !== attached) return@launch
                    if (attached.host.autofillBarHideGeneration != hideGeneration) return@launch
                    attached.host
                }
                host.focusedAutofillKind = FIELD_KIND_OTHER
                host.hideAddressAutofillBar()
            }
        }
        Log.i(TAG, "field-blur schedule hide")
    }

    /**
     * フォーカス中フレームのドキュメントが破棄されたとき。
     * 遷移後のページに古い候補バーを残さない。
     */
    fun onFocusPortDisconnected(session: GeckoSession) {
        val attached = synchronized(lock) {
            val attached = attachedSessions[session] ?: return
            attached.cancelJobs()
            attached.lastFieldKind = FIELD_KIND_OTHER
            attached
        }
        attached.host.focusedAutofillKind = FIELD_KIND_OTHER
        attached.host.hideAddressAutofillBar()
        Log.i(TAG, "focus port disconnected")
    }

    private fun presentCompletions(
        attached: Attached,
        addresses: List<Autocomplete.Address>,
        kind: AddressAutofillSuggestionKind,
    ) {
        if (synchronized(lock) { attachedSessions[attached.session] !== attached }) return
        val fillMode = kind.toFillMode()
        val items = if (kind == AddressAutofillSuggestionKind.Email) {
            addresses.filter { it.email.isNotBlank() }.distinctBy { it.email }
        } else {
            addresses
        }.mapNotNull { address ->
            val label = addressCompletionText(address, kind)
            if (label.isBlank()) {
                null
            } else {
                AddressAutofillSuggestionItem(
                    label = label,
                    kind = kind,
                    onClick = { fillSelectedAddress(attached, address, fillMode) },
                )
            }
        }
        if (items.isEmpty()) {
            attached.host.hideAddressAutofillBar()
            return
        }
        Log.i(TAG, "suggestion bar kind=$kind count=${items.size}")
        attached.host.showAddressAutofillBar(items)
    }

    /** [attachedSessions] の末尾へ動かし、セッションを伴わない通知の宛先にする。 */
    private fun moveToMostRecent(session: GeckoSession, attached: Attached) {
        attachedSessions.remove(session)
        attachedSessions[session] = attached
    }

    /**
     * [onAddressFetch] は shadow DOM 等でフォーカス通知が来ない場合のフォールバック。
     * 未確定 (null) のままなら出す。OTHER / EMAIL に確定したら出さない。
     */
    private fun shouldAbortAddressFetchAfterFocusSettled(attached: Attached): Boolean {
        return when (attached.lastFieldKind) {
            FIELD_KIND_ADDRESS, FIELD_KIND_NAME, null -> false
            FIELD_KIND_EMAIL, FIELD_KIND_OTHER -> true
            else -> true
        }
    }
}

class AddressAutofillDelegate(
    private val coordinator: AddressAutofillCoordinator,
    wrapped: Autofill.Delegate? = null,
) : Autofill.Delegate {
    private val mainHandler = Handler(Looper.getMainLooper())
    var wrapped: Autofill.Delegate? = wrapped
        private set

    /** GeckoView の delegate を包み、View の再 attach 後にも Android Autofill を維持する。 */
    fun bind(session: GeckoSession) {
        val current = session.autofillDelegate
        if (current !== this) {
            wrapped = (current as? AddressAutofillDelegate)?.wrapped ?: current
            session.autofillDelegate = this
        }
    }

    /**
     * GeckoView.releaseSession より先に delegate を外す。
     *
     * GeckoView は自身の delegate が直接設定されている場合しか解除しないため、ラップしたまま
     * release すると、遅れて届いた onNodeAdd が session=null の GeckoView を参照してクラッシュする。
     */
    fun unbindBeforeViewRelease(session: GeckoSession) {
        if (session.autofillDelegate === this) {
            session.autofillDelegate = null
        }
        wrapped = null
    }

    /** Composable の破棄時は、ラップ前の delegate をセッションへ戻す。 */
    fun restoreWrapped(session: GeckoSession) {
        if (session.autofillDelegate === this) {
            session.autofillDelegate = wrapped
        }
        wrapped = null
    }

    override fun onSessionStart(session: GeckoSession) {
        wrapped?.onSessionStart(session)
    }

    override fun onSessionCommit(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onSessionCommit(session, node, data)
    }

    override fun onSessionCancel(session: GeckoSession) {
        wrapped?.onSessionCancel(session)
    }

    override fun onNodeAdd(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeAdd(session, node, data)
    }

    override fun onNodeRemove(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeRemove(session, node, data)
    }

    override fun onNodeUpdate(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeUpdate(session, node, data)
    }

    override fun onNodeFocus(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeFocus(session, node, data)
        val attributes = node.attributes
        Log.i(
            TAG,
            "onNodeFocus tag=${node.tag} hint=${node.hint} attrs=$attributes id=${data.id}",
        )
        val kind = when {
            isEmailAutofillField(attributes) -> FIELD_KIND_EMAIL
            isNameAutofillField(attributes) -> FIELD_KIND_NAME
            isAddressAutofillField(attributes) -> FIELD_KIND_ADDRESS
            else -> FIELD_KIND_OTHER
        }
        // Gecko の Autofill 通知はメインスレッドではないことがある。
        // 候補バーは Compose 状態なので、ここでメインへ載せる。
        mainHandler.post { coordinator.onFieldFocus(session, kind) }
    }

    override fun onNodeBlur(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeBlur(session, node, data)
        // バーをタップすると Gecko 側は blur するため、即消しせず Coordinator 側で遅延 hide する
        mainHandler.post { coordinator.onFieldBlur(session) }
    }
}

private suspend fun scheduleSuggestionBar(
    addressRepository: AddressRepository,
    kind: AddressAutofillSuggestionKind,
    shouldAbort: () -> Boolean,
    present: (List<Autocomplete.Address>, AddressAutofillSuggestionKind) -> Unit,
    ioDispatcher: CoroutineDispatcher,
) {
    delay(ADDRESS_AUTOFILL_IME_READY_WAIT_MS)
    if (shouldAbort()) return
    val addresses = withContext(ioDispatcher) { addressRepository.getAll() }
        .map { it.toGeckoAddress() }
    if (addresses.isEmpty()) return
    if (shouldAbort()) return
    present(addresses, kind)
}

fun fillAddressOnSession(
    session: GeckoSession,
    address: Autocomplete.Address,
    mode: AddressAutofillFillMode,
) {
    val autofillSession = session.autofillSession
    val root = autofillSession.root
    val values = SparseArray<CharSequence>()
    fun walk(node: Autofill.Node) {
        val nodeData = autofillSession.dataFor(node)
        val value = resolveAddressAutofillValue(node.attributes, address, mode)
        if (value != null && value.isNotEmpty()) {
            values.put(nodeData.id, value)
        }
        node.children.forEach { walk(it) }
    }
    walk(root)
    Log.i(TAG, "autofill mode=$mode values=${values.size()}")
    if (values.size() > 0) {
        autofillSession.autofill(values)
    }
}

private const val TAG = "AddressAutofill"
internal const val ADDRESS_AUTOFILL_IME_READY_WAIT_MS = 150L
internal const val ADDRESS_AUTOFILL_BLUR_HIDE_WAIT_MS = 300L
private const val FILL_FOCUS_SUPPRESS_MS = 1_500L
const val FIELD_KIND_NAME = "name"
const val FIELD_KIND_ADDRESS = "address"
const val FIELD_KIND_EMAIL = "email"
const val FIELD_KIND_OTHER = "other"

fun isEmailAutofillField(attributes: Map<String, String>): Boolean {
    val inputType = attributes["type"].orEmpty()
    if (inputType.equals("email", ignoreCase = true)) return true
    val tokens = addressAutofillTokens(attributes)
    return tokens.any { it in EMAIL_TOKENS }
}

fun isNameAutofillField(attributes: Map<String, String>): Boolean {
    if (isEmailAutofillField(attributes)) return false
    val autocompleteTokens = attributes["autocomplete"]
        .orEmpty()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    if (autocompleteTokens.any { it in NAME_AUTOCOMPLETE_TOKENS }) return true
    val identityTokens = listOfNotNull(attributes["id"], attributes["name"])
        .flatMap { tokenizeAutofillAttribute(it) }
        .toSet()
    return identityTokens.any { it in NAME_ID_ALIAS_TOKENS }
}

fun isAddressAutofillField(attributes: Map<String, String>): Boolean {
    if (isEmailAutofillField(attributes)) return false
    val autocompleteTokens = attributes["autocomplete"]
        .orEmpty()
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    if (autocompleteTokens.any { it in ADDRESS_AUTOCOMPLETE_TOKENS }) return true
    val identityTokens = listOfNotNull(attributes["id"], attributes["name"])
        .flatMap { tokenizeAutofillAttribute(it) }
        .toSet()
    return identityTokens.any { it in ADDRESS_ID_ALIAS_TOKENS }
}

fun resolveAddressAutofillValue(
    attributes: Map<String, String>,
    address: Autocomplete.Address,
    mode: AddressAutofillFillMode = AddressAutofillFillMode.Address,
): String? {
    if (mode == AddressAutofillFillMode.Email) {
        return if (isEmailAutofillField(attributes)) address.email else null
    }
    if (isEmailAutofillField(attributes)) return null
    val tokens = addressAutofillTokens(attributes)
    return when {
        tokens.any { it in FAMILY_NAME_TOKENS } -> address.familyName
        tokens.any { it in GIVEN_NAME_TOKENS } -> address.givenName
        tokens.any { it in ADDITIONAL_NAME_TOKENS } -> address.additionalName
        tokens.any { it in FULL_NAME_TOKENS } -> address.name
        tokens.any { it in ORGANIZATION_TOKENS } -> address.organization
        tokens.any { it in STREET_ADDRESS_TOKENS } -> address.streetAddress
        tokens.any { it in ADDRESS_LINE1_TOKENS } -> address.streetAddress
        tokens.any { it in ADDRESS_LINE2_TOKENS } -> null
        tokens.any { it in ADDRESS_LINE3_TOKENS } -> null
        tokens.any { it in ADDRESS_LEVEL1_TOKENS } -> address.addressLevel1
        tokens.any { it in ADDRESS_LEVEL2_TOKENS } -> address.addressLevel2
        tokens.any { it in ADDRESS_LEVEL3_TOKENS } -> address.addressLevel3
        tokens.any { it in POSTAL_TOKENS } -> address.postalCode
        tokens.any { it in COUNTRY_TOKENS } -> address.country
        tokens.any { it in TEL_TOKENS } -> address.tel
        else -> null
    }
}

fun addressAutofillTokens(attributes: Map<String, String>): Set<String> {
    val raw = listOfNotNull(
        attributes["autocomplete"],
        attributes["id"],
        attributes["name"],
        attributes["autofillhint"],
    )
    return raw.flatMap { value -> tokenizeAutofillAttribute(value) }.toSet()
}

fun tokenizeAutofillAttribute(value: String): List<String> {
    val lower = value.lowercase()
    val delimited = lower.split(Regex("[\\s_]+")).filter { it.isNotEmpty() }
    val camel = value.split(Regex("(?<=[a-z0-9])(?=[A-Z])"))
        .map { it.lowercase() }
        .filter { it.isNotEmpty() }
    return (delimited + camel + listOf(lower)).map { token ->
        token.replace("_", "-")
    }
}

private val FAMILY_NAME_TOKENS = setOf("family-name", "familyname", "lastname", "last-name")
private val GIVEN_NAME_TOKENS = setOf("given-name", "givenname", "firstname", "first-name")
private val ADDITIONAL_NAME_TOKENS = setOf("additional-name", "additionalname", "middlename", "middle-name")
private val FULL_NAME_TOKENS = setOf("name")
private val ORGANIZATION_TOKENS = setOf("organization", "org", "company")
private val STREET_ADDRESS_TOKENS = setOf("street-address", "streetaddress")
private val ADDRESS_LINE1_TOKENS = setOf("address-line1", "addressline1")
private val ADDRESS_LINE2_TOKENS = setOf("address-line2", "addressline2")
private val ADDRESS_LINE3_TOKENS = setOf("address-line3", "addressline3")
private val STREET_FIELD_TOKENS = STREET_ADDRESS_TOKENS +
    ADDRESS_LINE1_TOKENS +
    ADDRESS_LINE2_TOKENS +
    ADDRESS_LINE3_TOKENS
private val ADDRESS_LEVEL1_TOKENS = setOf("address-level1", "addresslevel1", "state", "province")
private val ADDRESS_LEVEL2_TOKENS = setOf("address-level2", "addresslevel2", "city")
private val ADDRESS_LEVEL3_TOKENS = setOf("address-level3", "addresslevel3")
private val POSTAL_TOKENS = setOf("postal-code", "postalcode", "zip", "zipcode", "postcode")
private val COUNTRY_TOKENS = setOf("country", "country-name", "countryname")
private val TEL_TOKENS = setOf("tel", "telephone", "phone")
private val EMAIL_TOKENS = setOf("email")

fun addressCompletionText(
    address: Autocomplete.Address,
    kind: AddressAutofillSuggestionKind,
): String {
    return when (kind) {
        AddressAutofillSuggestionKind.Email -> address.email

        AddressAutofillSuggestionKind.Name -> addressDisplayName(address)

        AddressAutofillSuggestionKind.Address -> {
            addressDisplayAddress(address).ifEmpty { addressDisplayName(address) }
        }

        AddressAutofillSuggestionKind.FormField -> ""
    }
}

fun addressDisplayName(address: Autocomplete.Address): String {
    return "${address.familyName} ${address.givenName}".trim().ifEmpty { address.name }
}

fun addressDisplayAddress(address: Autocomplete.Address): String {
    return buildList {
        if (address.postalCode.isNotEmpty()) add("〒${address.postalCode}")
        if (address.addressLevel1.isNotEmpty()) add(address.addressLevel1)
        if (address.addressLevel2.isNotEmpty()) add(address.addressLevel2)
        if (address.addressLevel3.isNotEmpty()) add(address.addressLevel3)
        if (address.streetAddress.isNotEmpty()) add(address.streetAddress)
    }.joinToString(" ")
}

private val NAME_AUTOCOMPLETE_TOKENS = FAMILY_NAME_TOKENS +
    GIVEN_NAME_TOKENS +
    ADDITIONAL_NAME_TOKENS +
    FULL_NAME_TOKENS

private val NAME_ID_ALIAS_TOKENS = FAMILY_NAME_TOKENS +
    GIVEN_NAME_TOKENS +
    ADDITIONAL_NAME_TOKENS

private val ADDRESS_AUTOCOMPLETE_TOKENS = FAMILY_NAME_TOKENS +
    GIVEN_NAME_TOKENS +
    ADDITIONAL_NAME_TOKENS +
    FULL_NAME_TOKENS +
    ORGANIZATION_TOKENS +
    STREET_FIELD_TOKENS +
    ADDRESS_LEVEL1_TOKENS +
    ADDRESS_LEVEL2_TOKENS +
    ADDRESS_LEVEL3_TOKENS +
    POSTAL_TOKENS +
    COUNTRY_TOKENS +
    TEL_TOKENS

private val ADDRESS_ID_ALIAS_TOKENS = FAMILY_NAME_TOKENS +
    GIVEN_NAME_TOKENS +
    ADDITIONAL_NAME_TOKENS +
    ORGANIZATION_TOKENS +
    STREET_FIELD_TOKENS +
    ADDRESS_LEVEL1_TOKENS +
    ADDRESS_LEVEL2_TOKENS +
    ADDRESS_LEVEL3_TOKENS +
    POSTAL_TOKENS +
    COUNTRY_TOKENS +
    TEL_TOKENS

fun Autocomplete.Address.withoutEmail(): Autocomplete.Address {
    return Autocomplete.Address.Builder()
        .guid(guid)
        .name(name)
        .givenName(givenName)
        .additionalName(additionalName)
        .familyName(familyName)
        .organization(organization)
        .streetAddress(streetAddress)
        .addressLevel1(addressLevel1)
        .addressLevel2(addressLevel2)
        .addressLevel3(addressLevel3)
        .postalCode(postalCode)
        .country(country)
        .tel(tel)
        .email("")
        .build()
}
