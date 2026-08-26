package net.matsudamper.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import net.matsudamper.browser.resources.R as ResourcesR

sealed interface AddressEditScreenTestTags {
    val id: String

    val testTag get() = "${AddressEditScreenTestTags::class.java.name}#$id"

    data object Root : AddressEditScreenTestTags { override val id = "root" }
    data object SaveButton : AddressEditScreenTestTags { override val id = "save_button" }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddressEditScreen(
    uiState: AddressEditScreenUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag(AddressEditScreenTestTags.Root.testTag),
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "住所を追加" else "住所を編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = uiState.callbacks::onSave,
                        enabled = uiState.canSave,
                        modifier = Modifier.testTag(AddressEditScreenTestTags.SaveButton.testTag),
                    ) {
                        Text("保存")
                    }
                },
            )
        },
    ) { paddingValues ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .imeNestedScroll()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingSection(title = "氏名") {
                AddressField(
                    label = "姓",
                    value = uiState.familyName,
                    onValueChange = uiState.callbacks::onFamilyNameChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "名",
                    value = uiState.givenName,
                    onValueChange = uiState.callbacks::onGivenNameChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "ミドルネーム",
                    value = uiState.additionalName,
                    onValueChange = uiState.callbacks::onAdditionalNameChange,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingSection(title = "住所") {
                AddressField(
                    label = "郵便番号",
                    value = uiState.postalCode,
                    onValueChange = uiState.callbacks::onPostalCodeChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "都道府県",
                    value = uiState.addressLevel1,
                    onValueChange = uiState.callbacks::onAddressLevel1Change,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "市区町村",
                    value = uiState.addressLevel2,
                    onValueChange = uiState.callbacks::onAddressLevel2Change,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "町名・番地以降",
                    value = uiState.addressLevel3,
                    onValueChange = uiState.callbacks::onAddressLevel3Change,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "番地・建物名",
                    value = uiState.streetAddress,
                    onValueChange = uiState.callbacks::onStreetAddressChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "国・地域コード",
                    value = uiState.country,
                    onValueChange = uiState.callbacks::onCountryChange,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingSection(title = "連絡先") {
                AddressField(
                    label = "会社・組織",
                    value = uiState.organization,
                    onValueChange = uiState.callbacks::onOrganizationChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "電話番号",
                    value = uiState.tel,
                    onValueChange = uiState.callbacks::onTelChange,
                )
                Spacer(Modifier.height(8.dp))
                AddressField(
                    label = "メールアドレス",
                    value = uiState.email,
                    onValueChange = uiState.callbacks::onEmailChange,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AddressField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun PreviewAddressEditScreen() {
    AddressEditScreen(
            uiState = AddressEditScreenUiState(
                callbacks = object : AddressEditScreenUiState.Callbacks {
                    override fun onGivenNameChange(value: String) = Unit
                    override fun onAdditionalNameChange(value: String) = Unit
                    override fun onFamilyNameChange(value: String) = Unit
                    override fun onOrganizationChange(value: String) = Unit
                    override fun onStreetAddressChange(value: String) = Unit
                    override fun onAddressLevel1Change(value: String) = Unit
                    override fun onAddressLevel2Change(value: String) = Unit
                    override fun onAddressLevel3Change(value: String) = Unit
                    override fun onPostalCodeChange(value: String) = Unit
                    override fun onCountryChange(value: String) = Unit
                    override fun onTelChange(value: String) = Unit
                    override fun onEmailChange(value: String) = Unit
                    override fun onSave() = Unit
                },
                isNew = true,
                givenName = "太郎",
                additionalName = "",
                familyName = "山田",
                organization = "",
                streetAddress = "千代田1-1",
                addressLevel1 = "東京都",
                addressLevel2 = "千代田区",
                addressLevel3 = "",
                postalCode = "1000001",
                country = "JP",
                tel = "0312345678",
                email = "taro@example.com",
                canSave = true,
            ),
            onBack = {},
        )
}
