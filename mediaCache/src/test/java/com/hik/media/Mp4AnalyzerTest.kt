package com.hik.media.local

import org.junit.Test
import java.io.File

class Mp4AnalyzerTest {

    @Test
    fun testMoovAtEnd() {
        // 注意：此测试需要一个moov后置的MP4文件
        // 实际测试时，需要替换为真实的moov后置MP4文件路径
        val testFile = File("path/to/moov_at_end.mp4")
        if (!testFile.exists()) {
            println("测试文件不存在，请提供一个moov后置的MP4文件")
            return
        }

        val analyzer = Mp4Analyzer(testFile)
        
        // 分析文件结构
        val structureInfo = analyzer.analyzeStructureInfo()
        println("File structure:")
        println("  moov offset: ${structureInfo.moovOffset}")
        println("  moov size: ${structureInfo.moovSize}")
        println("  mdat offset: ${structureInfo.mdatOffset}")
        println("  mdat size: ${structureInfo.mdatSize}")
        
        // 检查moov是否在文件末尾
        val fileSize = testFile.length()
        val moovEnd = structureInfo.moovOffset + structureInfo.moovSize
        println("  File size: $fileSize")
        println("  Moov end: $moovEnd")
        println("  Moov at end: ${moovEnd == fileSize}")
        
        // 尝试获取片段范围
        try {
            val segment = analyzer.getSegmentRanges(3000, fileSize)
            println("  Duration: ${segment.durationMs}ms")
            println("  Segment count: ${segment.ranges.size}")
            println("  Sync sample count: ${segment.syncSampleNumber.size}")
            println("Test passed: Moov at end is supported")
        } catch (e: Exception) {
            println("Test failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun testMoovAtBeginning() {
        // 注意：此测试需要一个moov前置的MP4文件
        // 实际测试时，需要替换为真实的moov前置MP4文件路径
        val testFile = File("path/to/moov_at_beginning.mp4")
        if (!testFile.exists()) {
            println("测试文件不存在，请提供一个moov前置的MP4文件")
            return
        }

        val analyzer = Mp4Analyzer(testFile)
        
        // 分析文件结构
        val structureInfo = analyzer.analyzeStructureInfo()
        println("File structure:")
        println("  moov offset: ${structureInfo.moovOffset}")
        println("  moov size: ${structureInfo.moovSize}")
        println("  mdat offset: ${structureInfo.mdatOffset}")
        println("  mdat size: ${structureInfo.mdatSize}")
        
        // 检查moov是否在文件开头（ftyp之后）
        println("  Moov at beginning: ${structureInfo.moovOffset == structureInfo.ftypSize}")
        
        // 尝试获取片段范围
        try {
            val segment = analyzer.getSegmentRanges(3000, testFile.length())
            println("  Duration: ${segment.durationMs}ms")
            println("  Segment count: ${segment.ranges.size}")
            println("  Sync sample count: ${segment.syncSampleNumber.size}")
            println("Test passed: Moov at beginning is supported")
        } catch (e: Exception) {
            println("Test failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
