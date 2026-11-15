package com.seslikitap.core

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Testler için bellekte EPUB üretir. */
class TestEpub {
    private val files = LinkedHashMap<String, ByteArray>()

    fun file(path: String, text: String, charset: Charset = Charsets.UTF_8) = apply {
        files[path] = text.toByteArray(charset)
    }

    fun bytes(path: String, data: ByteArray) = apply { files[path] = data }

    fun build(storedMimetype: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            if (storedMimetype) {
                val m = "application/epub+zip".toByteArray()
                val e = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = m.size.toLong(); compressedSize = m.size.toLong()
                    crc = CRC32().apply { update(m) }.value
                }
                zos.putNextEntry(e); zos.write(m); zos.closeEntry()
            }
            for ((p, d) in files) {
                zos.putNextEntry(ZipEntry(p)); zos.write(d); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun open(): ZipArchive = ZipArchive.open(ByteArraySource(build()))

    companion object {
        fun container(opf: String = "OEBPS/content.opf") = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="$opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

        fun xhtml(body: String, title: String = "Bölüm") = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>$title</title></head>
<body>$body</body></html>"""
    }
}
