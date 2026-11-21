package app.localcart.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Money is integer paise everywhere; this formats it the Indian way (₹1,23,456.50). */
object Money {
    fun format(paise: Int): String {
        val negative = paise < 0
        val absolute = abs(paise.toLong())
        val rupees = absolute / 100
        val fraction = (absolute % 100).toInt()
        val body = groupIndian(rupees) + if (fraction != 0) ".%02d".format(Locale.US, fraction) else ""
        return (if (negative) "-₹" else "₹") + body
    }

    /** Indian digit grouping (12,34,567): last three digits, then pairs. Not every runtime's locale data does this. */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val pairs = head.reversed().chunked(2).joinToString(",").reversed()
        return "$pairs,$tail"
    }

    /** Parses what a shopkeeper types ("45", "45.5", "₹1,200") into paise, or null if invalid. */
    fun parseRupees(text: String): Int? {
        val cleaned = text.replace("₹", "").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value < 0 || value > 100_000) return null
        return (value * 100).roundToLong().toInt()
    }

    fun toInput(paise: Int): String = if (paise % 100 == 0) (paise / 100).toString() else "%.2f".format(paise / 100.0)
}

/** Selling units, mirrored from server/src/domain/units.ts. */
enum class Unit(val code: String, val label: String, val step: Double) {
    KG("kg", "kg", 0.25),
    G("g", "g", 50.0),
    L("l", "litre", 0.25),
    ML("ml", "ml", 50.0),
    PC("pc", "piece", 1.0),
    DOZEN("dozen", "dozen", 1.0),
    PACK("pack", "pack", 1.0),
    BUNCH("bunch", "bunch", 1.0),
    BOX("box", "box", 1.0);

    companion object {
        fun of(code: String): Unit = entries.firstOrNull { it.code == code } ?: PC
    }
}

object Quantities {
    /** "2 kg", "0.5 kg", "3 packs", "1 piece". */
    fun format(quantity: Double, unitCode: String): String {
        val unit = Unit.of(unitCode)
        val number = if (quantity % 1.0 == 0.0) quantity.toLong().toString() else trim(quantity)
        val label = when (unit) {
            Unit.PC -> if (quantity == 1.0) "piece" else "pieces"
            Unit.PACK -> if (quantity == 1.0) "pack" else "packs"
            Unit.BUNCH -> if (quantity == 1.0) "bunch" else "bunches"
            Unit.BOX -> if (quantity == 1.0) "box" else "boxes"
            Unit.L -> if (quantity == 1.0) "litre" else "litres"
            else -> unit.label
        }
        return "$number $label"
    }

    private fun trim(value: Double): String = "%.3f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    fun step(unitCode: String) = Unit.of(unitCode).step

    /** What one tap on "add" means: 1 kg, 1 litre, 250 g, 250 ml or one piece/pack. */
    fun defaultQuantity(unitCode: String): Double = when (Unit.of(unitCode)) {
        Unit.G, Unit.ML -> 250.0
        else -> 1.0
    }

    fun increase(quantity: Double, unitCode: String): Double = round(quantity + step(unitCode), unitCode)

    /** Decrease by one step; never below one step (remove the line instead). */
    fun decrease(quantity: Double, unitCode: String): Double = max(step(unitCode), round(quantity - step(unitCode), unitCode))

    fun round(quantity: Double, unitCode: String): Double {
        val step = step(unitCode)
        val rounded = (quantity / step).roundToInt() * step
        return max(step, "%.3f".format(Locale.US, rounded).toDouble())
    }

    fun lineTotal(quantity: Double, unitPricePaise: Int): Int = (quantity * unitPricePaise).roundToLong().toInt()
}

/** Order statuses and the copy we show for them. Mirrors server/src/domain/orderState.ts. */
object OrderStatus {
    const val DRAFT = "draft"
    const val PLACED = "placed"
    const val BILLED = "billed"
    const val ACCEPTED = "accepted"
    const val PACKED = "packed"
    const val OUT_FOR_DELIVERY = "out_for_delivery"
    const val DELIVERED = "delivered"
    const val CANCELLED = "cancelled"

    val fulfilment = listOf(ACCEPTED, PACKED, OUT_FOR_DELIVERY, DELIVERED)
    val active = setOf(DRAFT, PLACED, BILLED, ACCEPTED, PACKED, OUT_FOR_DELIVERY)

    fun label(status: String): String = when (status) {
        DRAFT -> "Draft cart"
        PLACED -> "Waiting for bill"
        BILLED -> "Bill to review"
        ACCEPTED -> "Accepted"
        PACKED -> "Packed"
        OUT_FOR_DELIVERY -> "Out for delivery"
        DELIVERED -> "Delivered"
        CANCELLED -> "Cancelled"
        else -> status
    }

    fun next(status: String): String? {
        val i = fulfilment.indexOf(status)
        return if (i < 0 || i == fulfilment.lastIndex) null else fulfilment[i + 1]
    }

    /** The shop's one-tap action label for the next fulfilment step. */
    fun nextActionLabel(status: String): String? = when (next(status)) {
        PACKED -> "Mark packed"
        OUT_FOR_DELIVERY -> "Out for delivery"
        DELIVERED -> "Mark delivered"
        else -> null
    }

    fun customerCanCancel(status: String) = status in setOf(DRAFT, PLACED, BILLED, ACCEPTED)
    fun shopCanCancel(status: String) = status in setOf(PLACED, BILLED, ACCEPTED, PACKED, OUT_FOR_DELIVERY)
}

object Payment {
    fun label(method: String?, status: String): String = when {
        method == null -> "Not chosen yet"
        method == "cod" && status == "confirmed" -> "Cash received"
        method == "cod" -> "Cash on delivery"
        status == "confirmed" -> "UPI payment received"
        status == "marked_paid" -> "UPI paid, waiting for shop to confirm"
        else -> "UPI payment pending"
    }
}

object Distance {
    fun format(km: Double?): String? = when {
        km == null -> null
        km < 1 -> "${(km * 1000).roundToInt().coerceAtLeast(50) / 50 * 50} m"
        else -> "%.1f km".format(Locale.US, km)
    }
}

object Hours {
    /** "07:00" → "7:00 am"; "22:30" → "10:30 pm". */
    fun pretty(hhmm: String): String {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val suffix = if (h < 12) "am" else "pm"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "%d:%02d %s".format(Locale.US, h12, m, suffix)
    }

    fun range(opensAt: String, closesAt: String): String =
        if (opensAt == closesAt) "Open 24 hours" else "${pretty(opensAt)} to ${pretty(closesAt)}"

    fun hhmm(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.US, hour, minute)
}

object Phone {
    /** Accepts +91 / 0 prefixes and spaces; returns the 10-digit number or null. */
    fun normalize(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val local = when {
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return local.takeIf { it.length == 10 && it.first() in '6'..'9' }
    }
}

object Upi {
    private val pattern = Regex("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9.-]{1,63}$")
    fun isValid(id: String) = pattern.matches(id.trim())
}

object ShopCategories {
    val all = listOf(
        "kirana" to "Kirana and grocery",
        "fruits_vegetables" to "Fruits and vegetables",
        "dairy" to "Dairy",
        "bakery" to "Bakery",
        "meat_fish" to "Meat and fish",
        "pharmacy" to "Pharmacy",
        "sweets_snacks" to "Sweets and snacks",
        "household" to "Household",
        "other" to "Other",
    )

    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: "Shop"

    fun short(code: String) = when (code) {
        "kirana" -> "Kirana"
        "fruits_vegetables" -> "Fruits & veg"
        "meat_fish" -> "Meat & fish"
        "sweets_snacks" -> "Sweets"
        else -> label(code)
    }
}
