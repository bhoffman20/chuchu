package com.jossephus.chuchu.ui.screens.AddServer

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport

data class AddServerForm(
    val id: Long? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val keyId: Long? = null,
    val privateKeyPem: String = "",
    val publicKeyOpenSsh: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String = "",
)

fun AddServerForm.canSave(): Boolean {
    if (name.isBlank() || host.isBlank() || username.isBlank()) return false
    return when (authMethod) {
        AuthMethod.Key,
        AuthMethod.KeyWithPassphrase -> keyId != null && privateKeyPem.isNotBlank()
        else -> true
    }
}

enum class ConnectionTestStatus {
    Idle,
    Running,
    Success,
    Error,
}

data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.Idle,
    val message: String? = null,
)
