package io.github.kazumibridge.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class CastCommand(val action: String, val namespace: String, val source: String?, val document: org.w3c.dom.Document)

object Soap {
    private const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
    private val supported = setOf("SetAVTransportURI", "Play", "Pause", "Stop", "Seek", "GetTransportInfo", "GetPositionInfo")

    fun parse(request: HttpRequest): CastCommand? {
        if (request.method != "POST" || request.header("soapaction")?.contains("AVTransport") != true) return null
        val xml = String(request.body, StandardCharsets.UTF_8)
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "XML declarations are not allowed" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(request.body))
        val bodies = document.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body")
        if (bodies.length != 1) return null
        val elements = (0 until bodies.item(0).childNodes.length).map { bodies.item(0).childNodes.item(it) }.filterIsInstance<Element>()
        if (elements.size != 1) return null
        val action = elements.single()
        val namespace = action.namespaceURI ?: return null
        if (!namespace.startsWith("urn:schemas-upnp-org:service:AVTransport:") || action.localName !in supported) return null
        val declared = request.header("soapaction")!!.trim('"').substringAfterLast('#')
        require(declared == action.localName) { "SOAPAction does not match body" }
        val urls = action.getElementsByTagName("CurrentURI")
        val source = if (action.localName == "SetAVTransportURI") {
            require(urls.length == 1) { "Missing CurrentURI" }
            urls.item(0).textContent.trim().also {
                val uri = java.net.URI(it)
                require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) { "Only HTTP(S) media URLs are supported" }
            }
        } else null
        return CastCommand(action.localName, namespace, source, document)
    }

    fun replaceMedia(request: HttpRequest, command: CastCommand, url: String, title: String = "Kazumi", duration: String? = null): HttpRequest {
        require(command.action == "SetAVTransportURI")
        command.document.getElementsByTagName("CurrentURI").item(0).textContent = url
        val durationAttribute = duration?.let { " duration=\"${escape(it)}\"" } ?: ""
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:video/mp4:*\"$durationAttribute>${escape(url)}</res></item></DIDL-Lite>"
        val metadataNodes = command.document.getElementsByTagName("CurrentURIMetaData")
        if (metadataNodes.length == 0) {
            val node = command.document.createElement("CurrentURIMetaData")
            node.textContent = metadata
            command.document.getElementsByTagNameNS(command.namespace, command.action).item(0).appendChild(node)
        } else metadataNodes.item(0).textContent = metadata
        val output = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }.transform(DOMSource(command.document), StreamResult(output))
        val bytes = output.toString().toByteArray(StandardCharsets.UTF_8)
        return request.copy(headers = request.headers - setOf("content-length", "transfer-encoding", "expect"), body = bytes)
    }

    fun success(action: String, namespace: String = AV, fields: String = ""): HttpResponse {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:${action}Response xmlns:u=\"${escape(namespace)}\">$fields</u:${action}Response></s:Body></s:Envelope>"
        return HttpResponse(200, "OK", mapOf("content-type" to "text/xml; charset=utf-8"), xml.toByteArray(StandardCharsets.UTF_8))
    }

    fun fault(code: Int, description: String): HttpResponse {
        val xml = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>${escape(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        return HttpResponse(500, "Internal Server Error", mapOf("content-type" to "text/xml; charset=utf-8"), xml.toByteArray(StandardCharsets.UTF_8))
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
