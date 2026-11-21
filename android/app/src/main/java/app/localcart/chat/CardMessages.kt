package app.localcart.chat

import ai.nexconn.chat.message.CustomMessageContent
import ai.nexconn.chat.message.model.MessagePersistent

/*
 * Custom Nexconn message types for order cards. The server posts them through the Platform Chat API
 * (server/src/services/cards.ts) with identical type names and field names. They are registered with
 * NCEngine.registerCustomMessages before connect, so they decode into these classes.
 *
 * Live state (status, payment, cart revision, cancel reason, rating) is not in the content: it lives
 * in message metadata, which the server updates as the order moves.
 */

data class CardLine(
    val name: String,
    val quantity: Double,
    val unit: String,
    /** -1 when the item is not in the catalog and the shop still has to price it. */
    val unitPricePaise: Int,
    val lineTotalPaise: Int,
    val available: Boolean,
    val matched: Boolean,
) {
    val isPriced: Boolean get() = unitPricePaise >= 0

    fun encode(): Map<String, Any?> = mapOf(
        "name" to name,
        "quantity" to quantity,
        "unit" to unit,
        "unitPricePaise" to unitPricePaise,
        "lineTotalPaise" to lineTotalPaise,
        "available" to available,
        "matched" to matched,
    )

    companion object {
        fun decodeList(raw: Any?): List<CardLine> {
            val list = raw as? List<*> ?: return emptyList()
            return list.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                CardLine(
                    name = m["name"] as? String ?: return@mapNotNull null,
                    quantity = (m["quantity"] as? Number)?.toDouble() ?: 1.0,
                    unit = m["unit"] as? String ?: "pc",
                    unitPricePaise = (m["unitPricePaise"] as? Number)?.toInt() ?: -1,
                    lineTotalPaise = (m["lineTotalPaise"] as? Number)?.toInt() ?: 0,
                    available = m["available"] as? Boolean ?: true,
                    matched = m["matched"] as? Boolean ?: true,
                )
            }
        }
    }
}

private fun Map<String, Any?>.str(key: String) = this[key] as? String ?: ""
private fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0

/** Common shape so the chat can treat every order card alike. */
interface OrderCard {
    val orderId: String
    val code: String
    val shopName: String
}

class CartCardMessage() : CustomMessageContent(), OrderCard {
    override var orderId: String = ""
    override var code: String = ""
    override var shopName: String = ""
    var rev: Int = 1
    var source: String = "text"
    var sourceText: String = ""
    var items: List<CardLine> = emptyList()
    var subtotalPaise: Int = 0
    var unpricedCount: Int = 0

    override fun messageType(): String = TYPE
    override fun persistentFlag(): Int = MessagePersistent.COUNT

    override fun encodeFields(): Map<String, Any?> = mapOf(
        "v" to 1,
        "orderId" to orderId,
        "code" to code,
        "shopName" to shopName,
        "rev" to rev,
        "source" to source,
        "sourceText" to sourceText,
        "items" to items.map { it.encode() },
        "subtotalPaise" to subtotalPaise,
        "unpricedCount" to unpricedCount,
    )

    override fun decodeFields(fields: Map<String, Any?>) {
        orderId = fields.str("orderId")
        code = fields.str("code")
        shopName = fields.str("shopName")
        rev = fields.int("rev").coerceAtLeast(1)
        source = fields.str("source")
        sourceText = fields.str("sourceText")
        items = CardLine.decodeList(fields["items"])
        subtotalPaise = fields.int("subtotalPaise")
        unpricedCount = fields.int("unpricedCount")
    }

    override fun searchableWords(): List<String> = listOf(code) + items.map { it.name }

    companion object {
        const val TYPE = "lcr:cart"
    }
}

class BillCardMessage() : CustomMessageContent(), OrderCard {
    override var orderId: String = ""
    override var code: String = ""
    override var shopName: String = ""
    var items: List<CardLine> = emptyList()
    var subtotalPaise: Int = 0
    var deliveryFeePaise: Int = 0
    var totalPaise: Int = 0
    var note: String = ""
    var upiId: String = ""

    override fun messageType(): String = TYPE
    override fun persistentFlag(): Int = MessagePersistent.COUNT

    override fun encodeFields(): Map<String, Any?> = mapOf(
        "v" to 1,
        "orderId" to orderId,
        "code" to code,
        "shopName" to shopName,
        "items" to items.map { it.encode() },
        "subtotalPaise" to subtotalPaise,
        "deliveryFeePaise" to deliveryFeePaise,
        "totalPaise" to totalPaise,
        "note" to note,
        "upiId" to upiId,
    )

    override fun decodeFields(fields: Map<String, Any?>) {
        orderId = fields.str("orderId")
        code = fields.str("code")
        shopName = fields.str("shopName")
        items = CardLine.decodeList(fields["items"])
        subtotalPaise = fields.int("subtotalPaise")
        deliveryFeePaise = fields.int("deliveryFeePaise")
        totalPaise = fields.int("totalPaise")
        note = fields.str("note")
        upiId = fields.str("upiId")
    }

    override fun searchableWords(): List<String> = listOf(code)

    companion object {
        const val TYPE = "lcr:bill"
    }
}

class StatusCardMessage() : CustomMessageContent(), OrderCard {
    override var orderId: String = ""
    override var code: String = ""
    override var shopName: String = ""
    var totalPaise: Int = 0
    var paymentMethod: String = ""
    var itemCount: Int = 0

    override fun messageType(): String = TYPE
    override fun persistentFlag(): Int = MessagePersistent.COUNT

    override fun encodeFields(): Map<String, Any?> = mapOf(
        "v" to 1,
        "orderId" to orderId,
        "code" to code,
        "shopName" to shopName,
        "totalPaise" to totalPaise,
        "paymentMethod" to paymentMethod,
        "itemCount" to itemCount,
    )

    override fun decodeFields(fields: Map<String, Any?>) {
        orderId = fields.str("orderId")
        code = fields.str("code")
        shopName = fields.str("shopName")
        totalPaise = fields.int("totalPaise")
        paymentMethod = fields.str("paymentMethod")
        itemCount = fields.int("itemCount")
    }

    override fun searchableWords(): List<String> = listOf(code)

    companion object {
        const val TYPE = "lcr:status"
    }
}

/** Card state carried in Nexconn message metadata (see server orderMetadata()). */
data class CardState(
    val status: String?,
    val payment: String?,
    val rev: Int?,
    val reason: String?,
    val rating: Int?,
) {
    companion object {
        fun from(metadata: Map<String, String>?): CardState = CardState(
            status = metadata?.get("status")?.takeIf { it.isNotBlank() },
            payment = metadata?.get("payment")?.takeIf { it.isNotBlank() },
            rev = metadata?.get("rev")?.toIntOrNull(),
            reason = metadata?.get("reason")?.takeIf { it.isNotBlank() },
            rating = metadata?.get("rating")?.toIntOrNull(),
        )
    }
}
