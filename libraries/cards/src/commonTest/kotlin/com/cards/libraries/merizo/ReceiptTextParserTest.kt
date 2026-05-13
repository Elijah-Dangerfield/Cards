package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ReceiptTextParserTest {
    
    private val parser = ReceiptTextParser()
    
    @Test
    fun `parses standard receipt format`() {
        val text = """
            Burger              $12.99
            Fries                $4.99
            Soda                 $2.50
            
            Subtotal           $20.48
            Tax                  $1.84
            Total              $22.32
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        println("Subtotal: ${receipt.subtotal}, Tax: ${receipt.tax}, Total: ${receipt.total}")
        
        assertEquals(3, receipt.lineItems.size, "Expected 3 items but got ${receipt.lineItems.size}: ${receipt.lineItems}")
        assertEquals("Burger", receipt.lineItems[0].name)
        assertEquals(12.99, receipt.lineItems[0].price)
        assertEquals("Fries", receipt.lineItems[1].name)
        assertEquals("Soda", receipt.lineItems[2].name)
        assertEquals(20.48, receipt.subtotal)
        assertEquals(1.84, receipt.tax)
        assertEquals(22.32, receipt.total)
    }
    
    @Test
    fun `parses grocery receipt with quantity lines`() {
        val text = """
            Califia Farms Oats Cream           $6.59
            Organic Valley Shrd Mozz           $5.99
            Halo Top Birthday Cake Light       $8.49
            Boylan Diet Cream Soda             $3.98
            Ppf Goldfish Cheddar               $3.69
            Organic Blueberries                $4.49
            Kind Dark Chocolate                $3.99
            Nasoya Organic Extra Firm Tofu     $8.98
            Reusable Bag                       $0.20
            
            Sub-Total                         $81.53
            Tax                                $0.84
            Total                             $82.37
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        
        // Should find the main items
        assertTrue(receipt.lineItems.any { it.name.contains("Califia", ignoreCase = true) },
            "Should find Califia item")
        assertTrue(receipt.lineItems.any { it.name.contains("Blueberries", ignoreCase = true) },
            "Should find Blueberries")
        assertTrue(receipt.lineItems.any { it.name.contains("Tofu", ignoreCase = true) },
            "Should find Tofu")
        
        // Tax should be detected
        assertEquals(0.84, receipt.tax, "Tax should be 0.84")
        
        // Total should be detected
        assertEquals(82.37, receipt.total, "Total should be 82.37")
    }
    
    @Test
    fun `parses restaurant receipt with tip`() {
        val text = """
            Ribeye Steak        $45.00
            Caesar Salad        $14.00
            Glass of Wine       $18.00
            Dessert             $12.00
            
            Subtotal            $89.00
            Tax                  $7.92
            Tip                 $17.80
            
            Total              $114.72
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        
        assertEquals(4, receipt.lineItems.size, "Expected 4 items")
        assertEquals(89.0, receipt.subtotal, "Subtotal should be 89.0")
        assertEquals(7.92, receipt.tax, "Tax should be 7.92")
        assertEquals(17.80, receipt.tip, "Tip should be 17.80")
        assertEquals(114.72, receipt.total, "Total should be 114.72")
    }
    
    @Test
    fun `handles items with leading asterisks for sale items`() {
        val text = """
            *Sale Item One      $5.99
            *Sale Item Two      $3.49
            Regular Item        $7.99
            
            Total              $17.47
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "'${it.name}' @ ${it.price}" }}")
        
        assertEquals(3, receipt.lineItems.size, "Expected 3 items")
        // Asterisks should be stripped
        assertTrue(receipt.lineItems.none { it.name.startsWith("*") }, "Should strip asterisks")
    }
    
    @Test
    fun `handles quantity prefix format`() {
        val text = """
            2 Hamburger         $19.98
            3x Fries            $11.97
            1 Shake              $5.99
            
            Total              $37.94
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "'${it.name}' qty=${it.quantity} @ ${it.price}" }}")
        
        // Should extract quantities
        val hamburger = receipt.lineItems.find { it.name.contains("Hamburger", ignoreCase = true) }
        assertNotNull(hamburger, "Should find hamburger item")
        assertEquals(2, hamburger.quantity, "Hamburger quantity should be 2")
    }
    
    @Test
    fun `ignores payment transaction lines`() {
        val text = """
            Item One            $10.00
            Item Two            $15.00
            
            Total               $25.00
            
            VISA                $25.00
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        
        assertEquals(2, receipt.lineItems.size, "Expected 2 items")
        assertTrue(receipt.lineItems.none { it.name.contains("VISA", ignoreCase = true) },
            "Should not include VISA")
    }
    
    @Test
    fun `handles trailing tax indicators`() {
        val text = """
            Milk                $4.99 F
            Bread               $3.49 F
            Candy               $2.99 T
            
            Subtotal           $11.47
            Tax                 $0.27
            Total              $11.74
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "'${it.name}' @ ${it.price}" }}")
        
        assertEquals(3, receipt.lineItems.size, "Expected 3 items")
    }
    
    @Test
    fun `stops parsing after total section`() {
        val text = """
            Item A              $10.00
            Item B              $20.00
            
            Total               $30.00
            
            SALE: $30.00
            AID: A00000031010
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        
        assertEquals(2, receipt.lineItems.size, "Expected 2 items")
        assertEquals(30.0, receipt.total)
    }
    
    @Test
    fun `handles empty or minimal input`() {
        val emptyReceipt = parser.parse("")
        assertEquals(0, emptyReceipt.lineItems.size)
        
        val justPrice = parser.parse("$10.00")
        assertEquals(0, justPrice.lineItems.size)
        
        val justText = parser.parse("Hello World")
        assertEquals(0, justText.lineItems.size)
    }
    
    @Test
    fun `filters unreasonable prices`() {
        val text = """
            Normal Item         $25.00
            Crazy Item      $99999.99
            
            Total              $25.00
        """.trimIndent()
        
        val receipt = parser.parse(text)
        
        println("Items found: ${receipt.lineItems.map { "${it.name} @ ${it.price}" }}")
        
        // Should only include the normal item (prices > $5000 filtered out)
        assertEquals(1, receipt.lineItems.size, "Expected 1 item (crazy price filtered)")
        assertEquals("Normal Item", receipt.lineItems[0].name)
    }
}
