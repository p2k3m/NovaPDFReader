package com.novapdf.reader.data

import com.novapdf.reader.data.PdfDocumentRepository
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageTreeRepairTest {

    @Test
    fun `rebalance splits oversized kids arrays`() {
        PDDocument().use { document ->
            repeat(64) { document.addPage(PDPage()) }

            val root = document.documentCatalog.pages.cosObject
            val initialKids = (root.getDictionaryObject(COSName.KIDS) as COSArray).size()
            assertTrue(initialKids >= 64)

            val rebalanced = PdfDocumentRepository.rebalancePdfPageTree(
                document = document,
                maxChildrenPerNode = 8,
            )
            assertTrue(rebalanced)

            val rootObject = document.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES)
            val rootDictionary = when (rootObject) {
                is COSDictionary -> rootObject
                is COSObject -> rootObject.`object` as? COSDictionary
                else -> null
            } ?: root

            val maxKids = maxKidsPerNode(rootDictionary)
            assertTrue("Expected <= 8 kids per node but found $maxKids", maxKids <= 8)
        }
    }

    private fun maxKidsPerNode(node: COSDictionary): Int {
        val kidsArray = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: return 0
        var maxKids = kidsArray.size()
        for (index in 0 until kidsArray.size()) {
            val child = kidsArray.getObject(index)
            val childDictionary = when (child) {
                is COSDictionary -> child
                is COSObject -> child.`object` as? COSDictionary
                else -> null
            } ?: continue
            maxKids = maxOf(maxKids, maxKidsPerNode(childDictionary))
        }
        return maxKids
    }
}
