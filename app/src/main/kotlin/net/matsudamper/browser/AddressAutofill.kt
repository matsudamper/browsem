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

/**
 * Gecko FormAutofill はトップ文書から iframe を辿るため、shadow DOM 内の
 * cross-origin iframe（MDN の interactive-example など）では選択プロンプトを出せない。
 *
 * フォールバックは次の2経路:
 * 1. `AutocompleteStorageDelegate.onAddressFetch`（フォーカス時のフィールド検出で確実に来る）
 * 2. GeckoView Autofill の `onNodeFocus` / WebExtension の focusin
 *
 * 住所欄とメール欄は別ダイアログにする。住所の選択ではメールを埋めない。
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
            promptDialogState.addressFillHandler = ::fillSelectedAddress
            promptDialogState.focusedAutofillKind = lastFieldKind
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
            attached?.promptDialogState?.addressFillHandler = null
            attached?.promptDialogState?.focusedAutofillKind = null
            attached = null
            lastFieldKind = null
        }
    }

    private fun fillSelectedAddress(
        address: Autocomplete.Address,
        mode: AddressAutofillFillMode,
    ) {
        val current = synchronized(lock) { attached } ?: return
        synchronized(lock) {
            suppressFocusUntilElapsed = SystemClock.elapsedRealtime() + FILL_FOCUS_SUPPRESS_MS
            suppressFocusKind = when (mode) {
                AddressAutofillFillMode.Email -> "email"
                AddressAutofillFillMode.Address -> "address"
            }
        }
        fillAddressOnSession(current.session, address, mode)
        fillExtension.fill(current.session, address, mode)
    }

    fun onAddressFetch(count: Int) {
        if (count <= 0) return
        val current = synchronized(lock) {
            if (lastFieldKind == "email") {
                Log.i(TAG, "onAddressFetch skipped: last field is email")
                return
            }
            if (isFocusSuppressed("address")) {
                Log.i(TAG, "onAddressFetch ignored after address fill")
                return
            }
            attached
        } ?: return
        Log.i(TAG, "onAddressFetch schedule address fallback count=$count")
        synchronized(lock) {
            showJob?.cancel()
            showJob = current.promptDialogState.coroutineScope.launch {
                scheduleAddressSelectFallback(
                    addressRepository = current.addressRepository,
                    promptDialogState = current.promptDialogState,
                    isEmailFieldFocused = { synchronized(lock) { lastFieldKind == "email" } },
                )
            }
        }
    }

    fun onFieldFocus(kind: String) {
        if (kind != "address" && kind != "email") return
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
        synchronized(lock) {
            showJob?.cancel()
            showJob = current.promptDialogState.coroutineScope.launch {
                if (kind == "email") {
                    current.promptDialogState.switchVisibleSelectToEmail()
                    scheduleEmailSelectFallback(
                        addressRepository = current.addressRepository,
                        promptDialogState = current.promptDialogState,
                    )
                } else {
                    current.promptDialogState.dismissEmailSelect()
                    scheduleAddressSelectFallback(
                        addressRepository = current.addressRepository,
                        promptDialogState = current.promptDialogState,
                        isEmailFieldFocused = { synchronized(lock) { lastFieldKind == "email" } },
                    )
                }
            }
        }
    }

    private fun isFocusSuppressed(kind: String): Boolean {
        return SystemClock.elapsedRealtime() < suppressFocusUntilElapsed &&
            suppressFocusKind == kind
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
            isEmailAutofillField(attributes) -> coordinator.onFieldFocus("email")
            isAddressAutofillField(attributes) -> coordinator.onFieldFocus("address")
        }
    }

    override fun onNodeBlur(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData,
    ) {
        wrapped?.onNodeBlur(session, node, data)
        // AlertDialog がフォーカスを奪うので blur ではダイアログを閉じない
    }
}

private suspend fun scheduleAddressSelectFallback(
    addressRepository: AddressRepository,
    promptDialogState: PromptDialogState,
    isEmailFieldFocused: () -> Boolean,
) {
    // Gecko の onAddressSelect が来るページではそちらを優先する
    delay(GECKO_PROMPT_WAIT_MS)
    if (isEmailFieldFocused()) return
    if (promptDialogState.hasVisibleAddressSelect() || promptDialogState.hasVisibleEmailSelect()) return
    val addresses = withContext(Dispatchers.IO) { addressRepository.getAll() }
    if (addresses.isEmpty()) return
    if (isEmailFieldFocused()) return
    if (promptDialogState.hasVisibleAddressSelect() || promptDialogState.hasVisibleEmailSelect()) return
    Log.i(TAG, "show fallback AddressSelectDialog count=${addresses.size}")
    promptDialogState.showAutofillAddressSelect(
        options = addresses.map { Autocomplete.AddressSelectOption(it.toGeckoAddress()) },
    )
}

private suspend fun scheduleEmailSelectFallback(
    addressRepository: AddressRepository,
    promptDialogState: PromptDialogState,
) {
    delay(GECKO_PROMPT_WAIT_MS)
    if (promptDialogState.hasVisibleAddressSelect() || promptDialogState.hasVisibleEmailSelect()) return
    val addresses = withContext(Dispatchers.IO) { addressRepository.getAll() }
        .map { it.toGeckoAddress() }
        .filter { it.email.isNotBlank() }
        .distinctBy { it.email }
    if (addresses.isEmpty()) return
    if (promptDialogState.hasVisibleAddressSelect() || promptDialogState.hasVisibleEmailSelect()) return
    Log.i(TAG, "show fallback EmailSelectDialog count=${addresses.size}")
    promptDialogState.showAutofillEmailSelect(
        options = addresses.map { Autocomplete.AddressSelectOption(it) },
    )
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
private const val GECKO_PROMPT_WAIT_MS = 400L
private const val FILL_FOCUS_SUPPRESS_MS = 1_500L

internal fun isEmailAutofillField(attributes: Map<String, String>): Boolean {
    val inputType = attributes["type"].orEmpty()
    if (inputType.equals("email", ignoreCase = true)) return true
    val tokens = addressAutofillTokens(attributes)
    return tokens.any { it in EMAIL_TOKENS }
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

