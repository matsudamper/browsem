package net.matsudamper.browser

import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.address.AddressRepository
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.GeckoSession

internal enum class AddressAutofillFillMode {
    Address,
    Email,
}

/** 候補バーに出す文言。フォーカス中の欄の種類に合わせる。 */
internal enum class AddressAutofillSuggestionKind {
    Name,
    Address,
    Email,
}

internal fun AddressAutofillSuggestionKind.toFillMode(): AddressAutofillFillMode {
    return when (this) {
        AddressAutofillSuggestionKind.Email -> AddressAutofillFillMode.Email
        AddressAutofillSuggestionKind.Name,
        AddressAutofillSuggestionKind.Address,
        -> AddressAutofillFillMode.Address
    }
}

internal fun suggestionKindFromFieldKind(kind: String?): AddressAutofillSuggestionKind {
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
internal class AddressAutofillCoordinator(
    private val fillExtension: AddressAutofillWebExtension,
) {
    private val lock = Any()
    private var attached: Attached? = null
    private var showJob: Job? = null
    private var lastFieldKind: String? = null
    private var suppressFocusUntilElapsed: Long = 0L
    private var suppressFocusKind: String? = null

    private class Attached(
        val session: GeckoSession,
        val promptDialogState: PromptDialogState,
        val addressRepository: AddressRepository,
    )

    fun attach(
        session: GeckoSession,
        promptDialogState: PromptDialogState,
        addressRepository: AddressRepository,
    ) {
        synchronized(lock) {
            attached = Attached(session, promptDialogState, addressRepository)
            promptDialogState.focusedAutofillKind = lastFieldKind
            promptDialogState.onAddressSelectOptions = { options ->
                val kind = suggestionKindFromFieldKind(synchronized(lock) { lastFieldKind })
                presentCompletions(options.map { it.value }, kind)
            }
        }
        fillExtension.onFieldFocus = { kind -> onFieldFocus(kind) }
        fillExtension.registerSession(session)
    }

    fun detach(session: GeckoSession) {
        fillExtension.onFieldFocus = null
        fillExtension.unregisterSession(session)
        synchronized(lock) {
            if (attached?.session !== session) return
            showJob?.cancel()
            showJob = null
            attached?.promptDialogState?.focusedAutofillKind = null
            attached?.promptDialogState?.onAddressSelectOptions = null
            attached?.promptDialogState?.addressAutofillBar = null
            attached = null
            lastFieldKind = null
        }
    }

    private fun fillSelectedAddress(
        address: Autocomplete.Address,
        mode: AddressAutofillFillMode,
    ) {
        val current = synchronized(lock) {
            showJob?.cancel()
            showJob = null
            suppressFocusUntilElapsed = SystemClock.elapsedRealtime() + FILL_FOCUS_SUPPRESS_MS
            suppressFocusKind = when (mode) {
                AddressAutofillFillMode.Email -> FIELD_KIND_EMAIL
                AddressAutofillFillMode.Address -> FIELD_KIND_ADDRESS
            }
            attached
        } ?: return
        current.promptDialogState.addressAutofillBar = null
        fillAddressOnSession(current.session, address, mode)
        fillExtension.fill(current.session, address, mode)
    }

    fun onAddressFetch(count: Int) {
        if (count <= 0) return
        val current = synchronized(lock) {
            if (lastFieldKind == FIELD_KIND_EMAIL) {
                Log.i(TAG, "onAddressFetch skipped: last field is email")
                return
            }
            if (isFocusSuppressed(FIELD_KIND_ADDRESS)) {
                Log.i(TAG, "onAddressFetch ignored after address fill")
                return
            }
            attached
        } ?: return
        Log.i(TAG, "onAddressFetch schedule suggestion bar count=$count")
        val kind = suggestionKindFromFieldKind(synchronized(lock) { lastFieldKind })
        synchronized(lock) {
            showJob?.cancel()
            showJob = current.promptDialogState.coroutineScope.launch {
                scheduleSuggestionBar(
                    addressRepository = current.addressRepository,
                    kind = kind,
                    shouldAbort = {
                        synchronized(lock) {
                            lastFieldKind == FIELD_KIND_EMAIL || isFocusSuppressed(FIELD_KIND_ADDRESS)
                        }
                    },
                    present = ::presentCompletions,
                )
            }
        }
    }

    fun onFieldFocus(kind: String) {
        if (kind != FIELD_KIND_ADDRESS && kind != FIELD_KIND_NAME && kind != FIELD_KIND_EMAIL) return
        val current = synchronized(lock) {
            if (isFocusSuppressed(kind)) {
                Log.i(TAG, "field-focus ignored after fill kind=$kind")
                return
            }
            lastFieldKind = kind
            attached
        } ?: return
        current.promptDialogState.focusedAutofillKind = kind
        Log.i(TAG, "field-focus kind=$kind")
        val suggestionKind = suggestionKindFromFieldKind(kind)
        synchronized(lock) {
            showJob?.cancel()
            showJob = current.promptDialogState.coroutineScope.launch {
                scheduleSuggestionBar(
                    addressRepository = current.addressRepository,
                    kind = suggestionKind,
                    shouldAbort = {
                        synchronized(lock) {
                            lastFieldKind != kind || isFocusSuppressed(kind)
                        }
                    },
                    present = ::presentCompletions,
                )
            }
        }
    }

    private fun presentCompletions(
        addresses: List<Autocomplete.Address>,
        kind: AddressAutofillSuggestionKind,
    ) {
        val current = synchronized(lock) { attached } ?: return
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
                AddressAutofillBarUiState.Item(
                    label = label,
                    kind = kind,
                    onClick = { fillSelectedAddress(address, fillMode) },
                )
            }
        }
        if (items.isEmpty()) return
        val uiState = AddressAutofillBarUiState(items = items)
        Log.i(TAG, "suggestion bar kind=$kind count=${uiState.items.size}")
        current.promptDialogState.addressAutofillBar = uiState
    }

    private fun isFocusSuppressed(kind: String): Boolean {
        if (SystemClock.elapsedRealtime() >= suppressFocusUntilElapsed) return false
        return when (suppressFocusKind) {
            FIELD_KIND_EMAIL -> kind == FIELD_KIND_EMAIL
            else -> kind == FIELD_KIND_ADDRESS || kind == FIELD_KIND_NAME
        }
    }
}

internal class AddressAutofillDelegate(
    private val coordinator: AddressAutofillCoordinator,
    internal val wrapped: Autofill.Delegate?,
) : Autofill.Delegate {

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
        when {
            isEmailAutofillField(attributes) -> coordinator.onFieldFocus(FIELD_KIND_EMAIL)
            isNameAutofillField(attributes) -> coordinator.onFieldFocus(FIELD_KIND_NAME)
            isAddressAutofillField(attributes) -> coordinator.onFieldFocus(FIELD_KIND_ADDRESS)
        }
    }

    override fun onNodeBlur(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeBlur(session, node, data)
        // バーをタップすると Gecko 側は blur するため、blur では候補を消さない
    }
}

private suspend fun scheduleSuggestionBar(
    addressRepository: AddressRepository,
    kind: AddressAutofillSuggestionKind,
    shouldAbort: () -> Boolean,
    present: (List<Autocomplete.Address>, AddressAutofillSuggestionKind) -> Unit,
) {
    delay(IME_READY_WAIT_MS)
    if (shouldAbort()) return
    val addresses = withContext(Dispatchers.IO) { addressRepository.getAll() }
        .map { it.toGeckoAddress() }
    if (addresses.isEmpty()) return
    if (shouldAbort()) return
    present(addresses, kind)
}

internal fun fillAddressOnSession(
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
private const val IME_READY_WAIT_MS = 150L
private const val FILL_FOCUS_SUPPRESS_MS = 1_500L
internal const val FIELD_KIND_NAME = "name"
internal const val FIELD_KIND_ADDRESS = "address"
internal const val FIELD_KIND_EMAIL = "email"

internal fun isEmailAutofillField(attributes: Map<String, String>): Boolean {
    val inputType = attributes["type"].orEmpty()
    if (inputType.equals("email", ignoreCase = true)) return true
    val tokens = addressAutofillTokens(attributes)
    return tokens.any { it in EMAIL_TOKENS }
}

internal fun isNameAutofillField(attributes: Map<String, String>): Boolean {
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

internal fun isAddressAutofillField(attributes: Map<String, String>): Boolean {
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

internal fun resolveAddressAutofillValue(
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
        tokens.any { it in STREET_TOKENS } -> address.streetAddress
        tokens.any { it in ADDRESS_LEVEL1_TOKENS } -> address.addressLevel1
        tokens.any { it in ADDRESS_LEVEL2_TOKENS } -> address.addressLevel2
        tokens.any { it in ADDRESS_LEVEL3_TOKENS } -> address.addressLevel3
        tokens.any { it in POSTAL_TOKENS } -> address.postalCode
        tokens.any { it in COUNTRY_TOKENS } -> address.country
        tokens.any { it in TEL_TOKENS } -> address.tel
        else -> null
    }
}

internal fun addressAutofillTokens(attributes: Map<String, String>): Set<String> {
    val raw = listOfNotNull(
        attributes["autocomplete"],
        attributes["id"],
        attributes["name"],
        attributes["autofillhint"],
    )
    return raw.flatMap { value -> tokenizeAutofillAttribute(value) }.toSet()
}

internal fun tokenizeAutofillAttribute(value: String): List<String> {
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
private val STREET_TOKENS = setOf(
    "street-address",
    "streetaddress",
    "address-line1",
    "address-line2",
    "address-line3",
)
private val ADDRESS_LEVEL1_TOKENS = setOf("address-level1", "addresslevel1", "state", "province")
private val ADDRESS_LEVEL2_TOKENS = setOf("address-level2", "addresslevel2", "city")
private val ADDRESS_LEVEL3_TOKENS = setOf("address-level3", "addresslevel3")
private val POSTAL_TOKENS = setOf("postal-code", "postalcode", "zip", "zipcode", "postcode")
private val COUNTRY_TOKENS = setOf("country", "country-name", "countryname")
private val TEL_TOKENS = setOf("tel", "telephone", "phone")
private val EMAIL_TOKENS = setOf("email")

internal fun addressCompletionText(
    address: Autocomplete.Address,
    kind: AddressAutofillSuggestionKind,
): String {
    return when (kind) {
        AddressAutofillSuggestionKind.Email -> address.email
        AddressAutofillSuggestionKind.Name -> addressDisplayName(address)
        AddressAutofillSuggestionKind.Address -> {
            addressDisplayAddress(address).ifEmpty { addressDisplayName(address) }
        }
    }
}

internal fun addressDisplayName(address: Autocomplete.Address): String {
    return "${address.familyName} ${address.givenName}".trim().ifEmpty { address.name }
}

internal fun addressDisplayAddress(address: Autocomplete.Address): String {
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
    STREET_TOKENS +
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
    STREET_TOKENS +
    ADDRESS_LEVEL1_TOKENS +
    ADDRESS_LEVEL2_TOKENS +
    ADDRESS_LEVEL3_TOKENS +
    POSTAL_TOKENS +
    COUNTRY_TOKENS +
    TEL_TOKENS

internal fun Autocomplete.Address.withoutEmail(): Autocomplete.Address {
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

