package ai.portfoliosense

import org.junit.Assert.*
import org.junit.Test

class BankAlertTest {
    private fun parse(text:String) = AlertParser.parse("VM-HDFCBK-S",text)
    @Test fun debitAndBalance() {
        val a=parse("A/c XX1234 debited by INR 1,23,456.78 for UPI. UPI Ref 123456789012. Avl Bal INR 9,999.00")!!
        assertEquals(12345678L,a.paise);assertEquals(FlowKind.SPEND,a.kind);assertEquals("123456789012",a.reference)
    }
    @Test fun salary(){ assertEquals(FlowKind.EARN,parse("INR 10,000.00 credited to your account via NEFT for salary")!!.kind) }
    @Test fun refund(){ assertEquals(FlowKind.REFUND,parse("Refund of Rs. 199.50 credited to your card")!!.kind) }
    @Test fun otp(){ assertNull(parse("OTP 392182 for INR 1500 spent on your card")) }
    @Test fun scheduled(){ assertNull(parse("Your account will be debited INR 500 tomorrow.")) }
    @Test fun declined(){ assertNull(parse("Card declined for Rs.20, amount not debited")) }
    @Test fun ownTransfer(){ assertEquals(FlowKind.EXCLUDED,parse("Account debited Rs.300 for self transfer")!!.kind) }
    @Test fun ignoresPersonalSender(){ assertNull(AlertParser.parse("+919876543210","Bank account debited ₹400")) }
    @Test fun rejectsAmbiguousMoney(){ assertNull(parse("Your card was debited INR 10 or INR 20.")) }
}
