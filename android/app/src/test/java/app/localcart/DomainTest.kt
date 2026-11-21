package app.localcart

import app.localcart.chat.CardLine
import app.localcart.chat.CardState
import app.localcart.chat.CartCardMessage
import app.localcart.domain.Distance
import app.localcart.domain.Hours
import app.localcart.domain.Money
import app.localcart.domain.OrderStatus
import app.localcart.domain.Payment
import app.localcart.domain.Phone
import app.localcart.domain.Quantities
import app.localcart.domain.Upi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun formatsIndianGrouping() {
        assertEquals("₹12,34,567", Money.format(123456700))
        assertEquals("₹45.50", Money.format(4550))
        assertEquals("₹0", Money.format(0))
    }

    @Test fun parsesWhatShopkeepersType() {
        assertEquals(4500, Money.parseRupees("45"))
        assertEquals(4550, Money.parseRupees("45.5"))
        assertEquals(120000, Money.parseRupees("₹1,200"))
        assertNull(Money.parseRupees(""))
        assertNull(Money.parseRupees("abc"))
        assertNull(Money.parseRupees("-4"))
    }

    @Test fun roundTripsInputText() {
        assertEquals("28", Money.toInput(2800))
        assertEquals("27.50", Money.toInput(2750))
    }
}

class QuantitiesTest {
    @Test fun formatsQuantitiesWithUnits() {
        assertEquals("2 kg", Quantities.format(2.0, "kg"))
        assertEquals("0.5 kg", Quantities.format(0.5, "kg"))
        assertEquals("3 packs", Quantities.format(3.0, "pack"))
        assertEquals("1 piece", Quantities.format(1.0, "pc"))
        assertEquals("1 litre", Quantities.format(1.0, "l"))
    }

    @Test fun stepsByUnitAndNeverBelowOneStep() {
        assertEquals(1.25, Quantities.increase(1.0, "kg"), 0.0001)
        assertEquals(0.25, Quantities.decrease(0.25, "kg"), 0.0001)
        assertEquals(1.0, Quantities.decrease(1.0, "pack"), 0.0001)
        assertEquals(250.0, Quantities.defaultQuantity("g"), 0.0001)
    }

    @Test fun computesLineTotalsInPaise() {
        assertEquals(6000, Quantities.lineTotal(2.0, 3000))
        assertEquals(2025, Quantities.lineTotal(0.5, 4050))
    }
}

class OrderStatusTest {
    @Test fun walksFulfilmentInOrder() {
        assertEquals(OrderStatus.PACKED, OrderStatus.next(OrderStatus.ACCEPTED))
        assertEquals(OrderStatus.DELIVERED, OrderStatus.next(OrderStatus.OUT_FOR_DELIVERY))
        assertNull(OrderStatus.next(OrderStatus.DELIVERED))
        assertNull(OrderStatus.next(OrderStatus.BILLED))
        assertEquals("Mark packed", OrderStatus.nextActionLabel(OrderStatus.ACCEPTED))
    }

    @Test fun cancellationRulesMatchTheServer() {
        assertTrue(OrderStatus.customerCanCancel(OrderStatus.DRAFT))
        assertFalse(OrderStatus.customerCanCancel(OrderStatus.OUT_FOR_DELIVERY))
        assertTrue(OrderStatus.shopCanCancel(OrderStatus.OUT_FOR_DELIVERY))
        assertFalse(OrderStatus.shopCanCancel(OrderStatus.DRAFT))
    }

    @Test fun describesPayment() {
        assertEquals("Cash on delivery", Payment.label("cod", "pending"))
        assertEquals("UPI paid, waiting for shop to confirm", Payment.label("upi", "marked_paid"))
        assertEquals("Not chosen yet", Payment.label(null, "none"))
    }
}

class InputTest {
    @Test fun normalisesIndianMobiles() {
        assertEquals("9876543210", Phone.normalize("+91 98765 43210"))
        assertEquals("9876543210", Phone.normalize("09876543210"))
        assertNull(Phone.normalize("5876543210"))
        assertNull(Phone.normalize("12345"))
    }

    @Test fun validatesUpiIds() {
        assertTrue(Upi.isValid("sharmastore@okaxis"))
        assertFalse(Upi.isValid("sharmastore"))
    }

    @Test fun formatsHoursAndDistance() {
        assertEquals("7:00 am", Hours.pretty("07:00"))
        assertEquals("10:30 pm", Hours.pretty("22:30"))
        assertEquals("12:00 am", Hours.pretty("00:00"))
        assertEquals("Open 24 hours", Hours.range("00:00", "00:00"))
        assertEquals("850 m", Distance.format(0.85))
        assertEquals("2.4 km", Distance.format(2.37))
    }
}

class CardDecodingTest {
    @Test fun decodesCartCardFromServerFields() {
        val card = CartCardMessage()
        card.decodeFields(
            mapOf(
                "orderId" to "o1",
                "code" to "K7Q4MX",
                "shopName" to "Sharma General Store",
                "rev" to 2,
                "source" to "voice",
                "sourceText" to "do kilo aloo",
                "items" to listOf(
                    mapOf("name" to "Potato (Aloo)", "quantity" to 2, "unit" to "kg", "unitPricePaise" to 3000, "lineTotalPaise" to 6000, "available" to true, "matched" to true),
                    mapOf("name" to "Maggi", "quantity" to 1.0, "unit" to "pack", "unitPricePaise" to -1, "lineTotalPaise" to 0, "available" to true, "matched" to false),
                ),
                "subtotalPaise" to 6000,
                "unpricedCount" to 1,
            ),
        )
        assertEquals("K7Q4MX", card.code)
        assertEquals(2, card.rev)
        assertEquals(2, card.items.size)
        assertEquals(2.0, card.items[0].quantity, 0.0)
        assertFalse(card.items[1].isPriced)
        assertEquals(card.encodeFields()["code"], "K7Q4MX")
    }

    @Test fun ignoresMalformedLines() {
        assertEquals(emptyList<CardLine>(), CardLine.decodeList("not a list"))
        assertEquals(1, CardLine.decodeList(listOf(mapOf("name" to "Eggs"), mapOf("quantity" to 2))).size)
    }

    @Test fun readsCardStateFromMetadata() {
        val state = CardState.from(mapOf("status" to "packed", "payment" to "marked_paid", "rev" to "3", "reason" to "", "rating" to ""))
        assertEquals("packed", state.status)
        assertEquals("marked_paid", state.payment)
        assertEquals(3, state.rev)
        assertNull(state.reason)
        assertNull(state.rating)
        assertNull(CardState.from(null).status)
    }
}
