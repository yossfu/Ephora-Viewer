package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.MeshDesc
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDBinary
import com.lumiyaviewer.lumiya.slproto.llsd.LLSDParser
import android.util.Log
import java.util.zip.Inflater
import kotlin.math.sqrt

object MeshDecoder {

    private const val TAG = "MeshDecoder"

    /**
     * 2.27l: caras de mesh cuyo asset no trae TexCoord0/TexCoord0Domain
     * utilizable y que por tanto entraron al VBO con UV=(0,0) (ver
     * buildMesh). No se reconstruye nada: solo se cuenta y se registra,
     * para atribuir caras UV-COLAPSADAS-POS-OK al decoder y no a la
     * geometria ni a TextureEntry. No cambia ningun pixel.
     */
    var uvFallbackFaces = 0
        private set

    const val MAX_MESH_VERSION = 999

    const val LOD_LOWEST = 0
    const val LOD_LOW = 1
    const val LOD_MEDIUM = 2
    const val LOD_HIGH = 3

    const val MAX_MESH_BYTES = 10 * 1024 * 1024
    const val MAX_MESH_VERTS = 250000
    const val MAX_MESH_FACES = 64

    private val LOD_KEYS = arrayOf("lowest_lod", "low_lod", "medium_lod", "high_lod")

    class MeshHeader(
        val version: Int,
        val headerSize: Int,
        val lodOffset: IntArray,
        val lodSize: IntArray
    ) {
        fun bestLod(): Int {
            for (lod in intArrayOf(LOD_HIGH, LOD_MEDIUM, LOD_LOW, LOD_LOWEST)) {
                if (lodSize[lod] > 0 && lodOffset[lod] >= 0) {
                    return lod
                }
            }
            return -1
        }
    }

    class HeaderResult(val header: MeshHeader?, val error: String?)

    class LodResult(val desc: MeshDesc?, val error: String?, val faces: Int = 0, val verts: Int = 0, val uvTraces: List<FaceUvTrace> = emptyList())

    class FaceUvTrace(
        val faceIndex: Int,
        val tcPresent: Boolean,
        val tcBytes: Int,
        val domainMin: FloatArray?,
        val domainMax: FloatArray?,
        val uvMin: FloatArray,
        val uvMax: FloatArray,
        val firstUvs: FloatArray,
        val fallback: Boolean,
        val reason: String
    )

    /**
     * 2.27w (solo diagnostico): auditoria del LOD inmediatamente despues de
     * decodificar el asset, antes de MeshBuilder/MeshDesc/VBO. No cambia
     * ningun vertice ni UV: solo cuenta y muestrea.
     */
    class LodFaceAudit(
        val faceIndex: Int,
        val hasTexCoord: Boolean,
        val texCoordCount: Int,
        val texCoordComponents: Int,
        val texCoordEncoding: String,
        val tcBytes: Int,
        val uvMin: FloatArray,
        val uvMax: FloatArray,
        val zeroCount: Int,
        val nonzeroCount: Int,
        val baseVertex: Int,
        val packed: FloatArray
    )

    class AssetLodAudit(
        val meshUuid: String,
        val lod: Int,
        val decoderVersion: Int,
        val assetFormat: String,
        val vertexCount: Int,
        val indexCount: Int,
        val faceCount: Int,
        val texcoordCount: Int,
        val uvMin: FloatArray,
        val uvMax: FloatArray,
        val uvZeroCount: Int,
        val uvNonzeroCount: Int,
        val faces: List<LodFaceAudit>
    )

    private val traceLock = Any()
    private val tracesByMesh = LinkedHashMap<String, List<FaceUvTrace>>()
    private val auditsByMesh = LinkedHashMap<String, AssetLodAudit>()

    fun noteAudit(audit: AssetLodAudit) {
        if (audit.meshUuid.isEmpty()) return
        synchronized(traceLock) {
            auditsByMesh[audit.meshUuid] = audit
            while (auditsByMesh.size > 512) {
                val it = auditsByMesh.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    fun auditFor(meshId: String): AssetLodAudit? {
        synchronized(traceLock) {
            return auditsByMesh[meshId]
        }
    }

    fun auditMeshCount(): Int {
        synchronized(traceLock) {
            return auditsByMesh.size
        }
    }

    /**
     * 2.27x (solo diagnostico): auditoria de bytes crudos por LOD, antes de
     * convertir a float. No construye MeshDesc ni cambia la seleccion de LOD.
     */
    class LodFaceRawAudit(
        val faceIndex: Int,
        val tcPresent: Boolean,
        val tcByteLength: Int,
        val texcoordCount: Int,
        val raw12: IntArray,
        val raw12Count: Int,
        val endianDemo: String,
        val domainMinRaw: String,
        val domainMaxRaw: String,
        val domainMinParsed: FloatArray?,
        val domainMaxParsed: FloatArray?,
        val rawZeroCount: Int,
        val rawNonzeroCount: Int,
        val decodedZeroCount: Int,
        val decodedNonzeroCount: Int,
        val caseLabel: String
    )

    class LodRawAudit(
        val lod: Int,
        val blockName: String,
        val present: Boolean,
        val faceCount: Int,
        val faces: List<LodFaceRawAudit>
    )

    class AssetAllLodAudit(
        val meshUuid: String,
        val decoderVersion: Int,
        val lods: List<LodRawAudit>
    )

    private val allLodAuditsByMesh = LinkedHashMap<String, AssetAllLodAudit>()

    fun lodBlockName(lod: Int): String = LOD_KEYS.getOrElse(lod) { "lod$lod" }

    fun allLodAuditFor(meshId: String): AssetAllLodAudit? {
        synchronized(traceLock) {
            return allLodAuditsByMesh[meshId]
        }
    }

    fun allLodAuditMeshCount(): Int {
        synchronized(traceLock) {
            return allLodAuditsByMesh.size
        }
    }

    private fun noteLodRawAudit(meshId: String, headerVersion: Int, lodAudit: LodRawAudit) {
        if (meshId.isEmpty()) return
        synchronized(traceLock) {
            val prev = allLodAuditsByMesh[meshId]
            val merged = if (prev == null) {
                AssetAllLodAudit(meshId, headerVersion, listOf(lodAudit))
            } else {
                AssetAllLodAudit(meshId, prev.decoderVersion, prev.lods.filter { it.lod != lodAudit.lod } + lodAudit)
            }
            allLodAuditsByMesh[meshId] = merged
            while (allLodAuditsByMesh.size > 128) {
                val it = allLodAuditsByMesh.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    /**
     * Audita un bloque LOD crudo (bytes zlib tal como llegan): infla, lee la
     * lista de caras y registra por cara los U16 crudos, el dominio LLSD
     * original vs parseado y la clasificacion CASE_*. Solo diagnostico.
     */
    fun auditLodRaw(meshId: String, lod: Int, bytes: ByteArray, headerVersion: Int = -1) {
        if (meshId.isEmpty() || bytes.isEmpty() || bytes.size > MAX_MESH_BYTES) return
        try {
            val raw = inflate(bytes)
            val parsed = LLSDBinary.decode(raw) ?: return
            val faces = parsed as? List<Any?> ?: return
            val out = ArrayList<LodFaceRawAudit>()
            for (faceIndex in faces.indices) {
                out.add(auditFaceRaw(faceIndex, faces[faceIndex]))
            }
            noteLodRawAudit(meshId, headerVersion, LodRawAudit(lod, lodBlockName(lod), true, faces.size, out))
        } catch (_: Throwable) {
        }
    }

    @Suppress("unchecked")
    private fun auditFaceRaw(faceIndex: Int, faceAny: Any?): LodFaceRawAudit {
        val face = faceAny as? Map<String, Any?> ?: return LodFaceRawAudit(
            faceIndex, false, 0, 0, IntArray(0), 0, "cara-ilegible",
            "ausente", "ausente", null, null, 0, 0, 0, 0, "CASE_NO_TEXCOORD_BLOCK"
        )
        val tcBytes = face["TexCoord0"] as? ByteArray
        val tcDomain = face["TexCoord0Domain"] as? Map<String, Any?>
        val minRawAny = tcDomain?.get("Min")
        val maxRawAny = tcDomain?.get("Max")
        val minRawStr = minRawAny?.toString() ?: "ausente"
        val maxRawStr = maxRawAny?.toString() ?: "ausente"
        val minP = vec2(minRawAny)
        val maxP = vec2(maxRawAny)
        if (tcBytes == null) {
            return LodFaceRawAudit(
                faceIndex, false, 0, 0, IntArray(0), 0, "sin-bloque-TexCoord0",
                minRawStr, maxRawStr, minP, maxP, 0, 0, 0, 0, "CASE_NO_TEXCOORD_BLOCK"
            )
        }
        val count = tcBytes.size / 4
        val raw12 = IntArray(24)
        val take = minOf(12, count)
        for (i in 0 until take) {
            raw12[i * 2] = u16(tcBytes, i * 4)
            raw12[i * 2 + 1] = u16(tcBytes, i * 4 + 2)
        }
        val endianDemo = if (count > 0) {
            val b0 = tcBytes[0].toInt() and 0xFF
            val b1 = tcBytes[1].toInt() and 0xFF
            val le = b0 or (b1 shl 8)
            val be = (b0 shl 8) or b1
            "BYTE0=" + b0 + " BYTE1=" + b1 + " LE=" + le + " BE=" + be + " USADO=LE(u16-little-endian)"
        } else {
            "vacio"
        }
        var rawZero = 0
        var rawNonzero = 0
        for (i in 0 until count) {
            if (u16(tcBytes, i * 4) == 0 && u16(tcBytes, i * 4 + 2) == 0) rawZero += 1 else rawNonzero += 1
        }
        var decZero = 0
        var decNonzero = 0
        if (minP != null && maxP != null && count > 0) {
            for (i in 0 until count) {
                val nu = u16(tcBytes, i * 4) / 65535.0
                val nv = u16(tcBytes, i * 4 + 2) / 65535.0
                val fu = nu * (maxP[0] - minP[0]) + minP[0]
                val fv = nv * (maxP[1] - minP[1]) + minP[1]
                if (fu == 0.0 && fv == 0.0) decZero += 1 else decNonzero += 1
            }
        }
        val caseLabel = classifyRaw(minRawStr, maxRawStr, minP, maxP, rawZero, rawNonzero, decZero, decNonzero)
        return LodFaceRawAudit(
            faceIndex, true, tcBytes.size, count, raw12, take, endianDemo,
            minRawStr, maxRawStr, minP, maxP,
            rawZero, rawNonzero, decZero, decNonzero, caseLabel
        )
    }

    private fun rawNumbers(s: String): List<Double> {
        val out = ArrayList<Double>()
        val re = Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")
        for (m in re.findAll(s)) {
            m.value.toDoubleOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun classifyRaw(
        minRawStr: String, maxRawStr: String,
        minP: FloatArray?, maxP: FloatArray?,
        rawZero: Int, rawNonzero: Int,
        decZero: Int, decNonzero: Int
    ): String {
        if (minP == null || maxP == null) {
            return if (minRawStr == "ausente" && maxRawStr == "ausente") {
                "CASE_SOURCE_DATA_ZERO"
            } else {
                "CASE_PARSE_DOMAIN_ERROR"
            }
        }
        val minNums = rawNumbers(minRawStr)
        val maxNums = rawNumbers(maxRawStr)
        if (minNums.size >= 2 && maxNums.size >= 2) {
            val ok = kotlin.math.abs(minNums[0] - minP[0]) <= 1e-6 &&
                kotlin.math.abs(minNums[1] - minP[1]) <= 1e-6 &&
                kotlin.math.abs(maxNums[0] - maxP[0]) <= 1e-6 &&
                kotlin.math.abs(maxNums[1] - maxP[1]) <= 1e-6
            if (!ok) return "CASE_PARSE_DOMAIN_ERROR"
        }
        val dMinZero = minP[0] == 0f && minP[1] == 0f
        val dMaxZero = maxP[0] == 0f && maxP[1] == 0f
        if (rawNonzero == 0 && dMinZero && dMaxZero) return "CASE_SOURCE_DATA_ZERO"
        if (minP[0] == maxP[0] && minP[1] == maxP[1]) return "CASE_DEGENERATE_DOMAIN"
        if (rawNonzero > 0 && decNonzero == 0) return "CASE_DECODE_MATH_ERROR"
        return "CASE_DATA_OK"
    }

    fun noteTraces(meshId: String, traces: List<FaceUvTrace>) {
        if (meshId.isEmpty() || traces.isEmpty()) return
        synchronized(traceLock) {
            tracesByMesh[meshId] = traces
            while (tracesByMesh.size > 512) {
                val it = tracesByMesh.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    fun tracesFor(meshId: String): List<FaceUvTrace>? {
        synchronized(traceLock) {
            return tracesByMesh[meshId]?.toList()
        }
    }

    fun traceMeshCount(): Int {
        synchronized(traceLock) {
            return tracesByMesh.size
        }
    }

    fun parseHeader(bytes: ByteArray): HeaderResult {
        if (bytes.isEmpty()) {
            return HeaderResult(null, "cabecera vacia")
        }
        var start = -1
        if ((bytes[0].toInt() and 0xFF) == MAP_MARK) {
            start = 0
        } else {
            val limit = minOf(bytes.size, 256)
            for (i in 0 until limit) {
                if ((bytes[i].toInt() and 0xFF) == MAP_MARK) {
                    start = i
                    break
                }
            }
        }
        if (start < 0) {
            return HeaderResult(null, "sin marca LLSD en cabecera (" + bytes.size + " bytes)")
        }
        val decoded: Pair<Any?, Int>
        try {
            decoded = LLSDBinary.decodeWithLength(bytes, start)
        } catch (t: Throwable) {
            return HeaderResult(null, "cabecera ilegible: " + (t.message ?: t.javaClass.simpleName))
        }
        val map = decoded.first as? Map<String, Any?>
            ?: return HeaderResult(null, "cabecera no es mapa LLSD")
        val version = LLSDParser.asInt(map["version"], -1)
        if (version < 0) {
            return HeaderResult(null, "cabecera sin version")
        }
        if (version > MAX_MESH_VERSION) {
            return HeaderResult(null, "version no soportada: " + version)
        }
        val offsets = IntArray(4) { -1 }
        val sizes = IntArray(4) { -1 }
        for (lod in 0..3) {
            val section = map[LOD_KEYS[lod]] as? Map<String, Any?> ?: continue
            offsets[lod] = LLSDParser.asInt(section["offset"], -1)
            sizes[lod] = LLSDParser.asInt(section["size"], -1)
        }
        if (sizes.all { it <= 0 }) {
            return HeaderResult(null, "cabecera sin LOD (version " + version + ")")
        }
        return HeaderResult(MeshHeader(version, start + decoded.second, offsets, sizes), null)
    }

    fun decodeLod(bytes: ByteArray, meshId: String = "", lod: Int = -1, headerVersion: Int = -1): LodResult {
        if (bytes.isEmpty()) {
            return LodResult(null, "LOD vacio")
        }
        if (bytes.size > MAX_MESH_BYTES) {
            return LodResult(null, "LOD demasiado grande (" + bytes.size + " bytes)")
        }
        val raw: ByteArray
        try {
            raw = inflate(bytes)
        } catch (t: Throwable) {
            return LodResult(null, "zlib: " + (t.message ?: t.javaClass.simpleName))
        }
        val parsed: Any?
        try {
            parsed = LLSDBinary.decode(raw)
        } catch (t: Throwable) {
            return LodResult(null, "LLSD: " + (t.message ?: t.javaClass.simpleName))
        }
        val faces = parsed as? List<Any?> ?: return LodResult(null, "LOD no es lista de caras")
        val built = buildMesh(faces, meshId, lod, headerVersion)
        if (meshId.isNotEmpty()) {
            noteTraces(meshId, built.uvTraces)
        }
        return built
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        var last: Throwable? = null
        for (nowrap in arrayOf(false, true)) {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(bytes)
                val out = java.io.ByteArrayOutputStream(minOf(bytes.size * 4, 1 shl 20))
                val chunk = ByteArray(65536)
                while (!inflater.finished()) {
                    val n = inflater.inflate(chunk)
                    if (n == 0) {
                        if (inflater.needsInput()) {
                            break
                        }
                        if (inflater.needsDictionary()) {
                            throw java.util.zip.DataFormatException("diccionario zlib")
                        }
                        break
                    }
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_MESH_BYTES) {
                        throw java.util.zip.DataFormatException("LOD inflado supera tope")
                    }
                }
                if (out.size() == 0) {
                    throw java.util.zip.DataFormatException("inflado vacio")
                }
                return out.toByteArray()
            } catch (t: Throwable) {
                last = t
            } finally {
                inflater.end()
            }
        }
        throw last ?: java.util.zip.DataFormatException("inflate fallo")
    }

    @Suppress("unchecked")
    private fun buildMesh(faces: List<Any?>, meshId: String = "", lod: Int = -1, headerVersion: Int = -1): LodResult {
        val positions = ArrayList<Float>(4096)
        val normals = ArrayList<Float>(4096)
        val uvs = ArrayList<Float>(2730)
        val indices = ArrayList<Int>(4096)
        val groups = ArrayList<Int>(32)
        val traces = ArrayList<FaceUvTrace>()
        val faceAudits = ArrayList<LodFaceAudit>()
        var meshZero = 0
        var meshNonzero = 0
        var meshTexVerts = 0
        var meshMinU = Float.MAX_VALUE
        var meshMinV = Float.MAX_VALUE
        var meshMaxU = -Float.MAX_VALUE
        var meshMaxV = -Float.MAX_VALUE
        var keptFaces = 0
        var skippedFaces = 0
        var flatNormals = 0
        for (faceIndex in faces.indices) {
            if (keptFaces >= MAX_MESH_FACES) {
                break
            }
            val face = faces[faceIndex] as? Map<String, Any?> ?: continue
            if (face.containsKey("NoGeometry")) {
                skippedFaces += 1
                continue
            }
            val posBytes = face["Position"] as? ByteArray ?: continue
            val idxBytes = face["TriangleList"] as? ByteArray ?: continue
            if (posBytes.isEmpty() || idxBytes.isEmpty()) {
                skippedFaces += 1
                continue
            }
            val faceVerts = posBytes.size / 6
            var faceIdx = idxBytes.size / 2
            faceIdx -= faceIdx % 3
            if (faceVerts <= 0 || faceIdx < 3) {
                skippedFaces += 1
                continue
            }
            if (positions.size / 3 + faceVerts > MAX_MESH_VERTS) {
                return LodResult(null, "malla supera tope de vertices", keptFaces, positions.size / 3, traces)
            }
            val posDomain = face["PositionDomain"] as? Map<String, Any?>
            val minPRaw = vec3(posDomain?.get("Min"))
            if (minPRaw == null) {
                skippedFaces += 1
                continue
            }
            val minP = minPRaw
            val maxPRaw = vec3(posDomain?.get("Max"))
            if (maxPRaw == null) {
                skippedFaces += 1
                continue
            }
            val maxP = maxPRaw
            val base = positions.size / 3
            val facePos = FloatArray(9)
            val faceIndices = IntArray(faceIdx)
            var faceOk = true
            for (j in 0 until faceIdx) {
                val idx = u16(idxBytes, j * 2)
                if (idx >= faceVerts) {
                    faceOk = false
                    break
                }
                faceIndices[j] = base + idx
            }
            if (!faceOk) {
                skippedFaces += 1
                continue
            }
            for (v in 0 until faceVerts) {
                val o = v * 6
                val x = u16(posBytes, o)
                val y = u16(posBytes, o + 2)
                val z = u16(posBytes, o + 4)
                val px = minP[0] + (x / 65535f) * (maxP[0] - minP[0])
                val py = minP[1] + (y / 65535f) * (maxP[1] - minP[1])
                val pz = minP[2] + (z / 65535f) * (maxP[2] - minP[2])
                positions.add(px)
                positions.add(py)
                positions.add(pz)
                if (v < 3) {
                    facePos[v * 3] = px
                    facePos[v * 3 + 1] = py
                    facePos[v * 3 + 2] = pz
                }
            }
            val normBytes = face["Normal"] as? ByteArray
            if (normBytes != null && normBytes.size >= faceVerts * 6) {
                for (v in 0 until faceVerts) {
                    val o = v * 6
                    normals.add((u16(normBytes, o) / 65535f) * 2f - 1f)
                    normals.add((u16(normBytes, o + 2) / 65535f) * 2f - 1f)
                    normals.add((u16(normBytes, o + 4) / 65535f) * 2f - 1f)
                }
            } else {
                for (v in 0 until faceVerts) {
                    normals.add(0f)
                    normals.add(0f)
                    normals.add(0f)
                }
                flatNormals += 1
            }
            val tcBytes = face["TexCoord0"] as? ByteArray
            val tcDomain = face["TexCoord0Domain"] as? Map<String, Any?>
            val minT = vec2(tcDomain?.get("Min"))
            val maxT = vec2(tcDomain?.get("Max"))
            if (tcBytes != null && tcBytes.size >= faceVerts * 4 && minT != null && maxT != null) {
                var tMinU = Float.MAX_VALUE
                var tMinV = Float.MAX_VALUE
                var tMaxU = -Float.MAX_VALUE
                var tMaxV = -Float.MAX_VALUE
                var fZero = 0
                var fNonzero = 0
                val first = FloatArray(minOf(6, faceVerts * 2))
                val faceUv = FloatArray(6)
                for (v in 0 until faceVerts) {
                    val o = v * 4
                    val du = minT[0] + (u16(tcBytes, o) / 65535f) * (maxT[0] - minT[0])
                    val dv = minT[1] + (u16(tcBytes, o + 2) / 65535f) * (maxT[1] - minT[1])
                    uvs.add(du)
                    uvs.add(dv)
                    if (du == 0f && dv == 0f) {
                        fZero += 1
                    } else {
                        fNonzero += 1
                    }
                    if (du < tMinU) tMinU = du
                    if (dv < tMinV) tMinV = dv
                    if (du > tMaxU) tMaxU = du
                    if (dv > tMaxV) tMaxV = dv
                    if (du < meshMinU) meshMinU = du
                    if (dv < meshMinV) meshMinV = dv
                    if (du > meshMaxU) meshMaxU = du
                    if (dv > meshMaxV) meshMaxV = dv
                    if (v < 3) {
                        first[v * 2] = du
                        first[v * 2 + 1] = dv
                        faceUv[v * 2] = du
                        faceUv[v * 2 + 1] = dv
                    }
                }
                meshZero += fZero
                meshNonzero += fNonzero
                meshTexVerts += faceVerts
                faceAudits.add(
                    LodFaceAudit(
                        faceIndex, true, faceVerts, 2, "u16-normalized-TexCoord0Domain",
                        tcBytes.size,
                        floatArrayOf(tMinU, tMinV), floatArrayOf(tMaxU, tMaxV),
                        fZero, fNonzero, base, packSamples(facePos, faceUv)
                    )
                )
                traces.add(
                    FaceUvTrace(
                        faceIndex, true, tcBytes.size,
                        floatArrayOf(minT[0], minT[1]), floatArrayOf(maxT[0], maxT[1]),
                        floatArrayOf(tMinU, tMinV), floatArrayOf(tMaxU, tMaxV), first, false, "ok"
                    )
                )
            } else {
                val reason = if (tcBytes == null) "sin-TexCoord0" else if (tcBytes.size < faceVerts * 4) "TexCoord0-corto" else "sin-TexCoord0Domain"
                traces.add(
                    FaceUvTrace(
                        faceIndex, tcBytes != null, tcBytes?.size ?: 0,
                        minT?.let { floatArrayOf(it[0], it[1]) }, maxT?.let { floatArrayOf(it[0], it[1]) },
                        floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), FloatArray(0), true, reason
                    )
                )
                uvFallbackFaces += 1
                Log.w(TAG, "cara sin TexCoord0 utilizable: UV=(0,0) en " + faceVerts + " vertices (posiciones intactas)")
                for (v in 0 until faceVerts) {
                    uvs.add(0f)
                    uvs.add(0f)
                }
                meshZero += faceVerts
                if (0f < meshMinU) meshMinU = 0f
                if (0f < meshMinV) meshMinV = 0f
                if (0f > meshMaxU) meshMaxU = 0f
                if (0f > meshMaxV) meshMaxV = 0f
                faceAudits.add(
                    LodFaceAudit(
                        faceIndex, tcBytes != null, faceVerts, 2, "u16-normalized-TexCoord0Domain",
                        tcBytes?.size ?: 0,
                        floatArrayOf(0f, 0f), floatArrayOf(0f, 0f),
                        faceVerts, 0, base, packSamples(facePos, FloatArray(6))
                    )
                )
            }
            val first = indices.size
            for (j in 0 until faceIdx) {
                indices.add(faceIndices[j])
            }
            val count = indices.size - first
            if (count < 3) {
                skippedFaces += 1
                continue
            }
            groups.add(faceIndex)
            groups.add(first)
            groups.add(count)
            keptFaces += 1
        }
        if (keptFaces == 0) {
            return LodResult(null, "sin caras con geometria (" + skippedFaces + " vacias)", 0, 0, traces)
        }
        if (flatNormals > 0) {
            smoothNormals(positions, normals, indices)
        }
        val verts = FloatArray(positions.size + (positions.size / 3) * 5)
        var p = 0
        var n = 0
        var u = 0
        var o = 0
        val nv = positions.size / 3
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (v in 0 until nv) {
            val x = positions[p++]
            val y = positions[p++]
            val z = positions[p++]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
            verts[o++] = x
            verts[o++] = y
            verts[o++] = z
            verts[o++] = normals[n++]
            verts[o++] = normals[n++]
            verts[o++] = normals[n++]
            verts[o++] = uvs[u++]
            verts[o++] = uvs[u++]
        }
        val desc = MeshDesc(
            verts,
            indices.toIntArray(),
            groups.toIntArray(),
            floatArrayOf(minX, minY, minZ),
            floatArrayOf(maxX, maxY, maxZ)
        )
        val problem = desc.geometryProblem()
        if (problem != null) {
            return LodResult(null, "geometria invalida: " + problem, keptFaces, nv, traces)
        }
        if (meshId.isNotEmpty()) {
            noteAudit(
                AssetLodAudit(
                    meshId, lod, headerVersion, "SLMesh-LLSD-zlib",
                    nv, indices.size, keptFaces, meshTexVerts,
                    floatArrayOf(meshMinU, meshMinV), floatArrayOf(meshMaxU, meshMaxV),
                    meshZero, meshNonzero, faceAudits.toList()
                )
            )
        }
        return LodResult(desc, null, keptFaces, nv, traces)
    }

    private fun smoothNormals(pos: List<Float>, nrm: ArrayList<Float>, idx: List<Int>) {
        val nv = pos.size / 3
        val accX = FloatArray(nv)
        val accY = FloatArray(nv)
        val accZ = FloatArray(nv)
        var t = 0
        while (t + 2 < idx.size) {
            val a = idx[t] * 3
            val b = idx[t + 1] * 3
            val c = idx[t + 2] * 3
            val abx = pos[b] - pos[a]
            val aby = pos[b + 1] - pos[a + 1]
            val abz = pos[b + 2] - pos[a + 2]
            val acx = pos[c] - pos[a]
            val acy = pos[c + 1] - pos[a + 1]
            val acz = pos[c + 2] - pos[a + 2]
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) {
                nx /= len
                ny /= len
                nz /= len
            }
            for (k in 0..2) {
                val v = idx[t + k]
                accX[v] += nx
                accY[v] += ny
                accZ[v] += nz
            }
            t += 3
        }
        for (v in 0 until nv) {
            var nx = accX[v]
            var ny = accY[v]
            var nz = accZ[v]
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) {
                nx /= len
                ny /= len
                nz /= len
            } else {
                nx = 0f
                ny = 0f
                nz = 1f
            }
            nrm[v * 3] = nx
            nrm[v * 3 + 1] = ny
            nrm[v * 3 + 2] = nz
        }
    }

    private fun packSamples(pos: FloatArray, uv: FloatArray): FloatArray {
        val out = FloatArray(15)
        for (v in 0 until 3) {
            out[v * 5] = pos[v * 3]
            out[v * 5 + 1] = pos[v * 3 + 1]
            out[v * 5 + 2] = pos[v * 3 + 2]
            out[v * 5 + 3] = uv[v * 2]
            out[v * 5 + 4] = uv[v * 2 + 1]
        }
        return out
    }

    private fun vec3(value: Any?): FloatArray? {
        val list = value as? List<Any?> ?: return null
        if (list.size < 3) {
            return null
        }
        val out = FloatArray(3)
        for (i in 0..2) {
            out[i] = (list[i] as? Number)?.toFloat() ?: return null
        }
        return out
    }

    private fun vec2(value: Any?): FloatArray? {
        val list = value as? List<Any?> ?: return null
        if (list.size < 2) {
            return null
        }
        val out = FloatArray(2)
        for (i in 0..1) {
            out[i] = (list[i] as? Number)?.toFloat() ?: return null
        }
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private const val MAP_MARK = '{'.code
}
