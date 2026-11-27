package de.cracky.notizen.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Kotlin-Port des Delta-Formats aus ot.py / static/js/delta.js (quill-delta-kompatibel).
 * Längen zählen in UTF-16-Codeeinheiten – genau wie Kotlins String.length, der Server und JavaScript.
 *
 * Attributwerte: true (bold/italic/underline), 1|2 (header) oder null (= entfernen, nur bei retain).
 */
typealias Attrs = Map<String, Any?>

private const val INF = Int.MAX_VALUE

data class Op(
    val insert: String? = null,
    val retain: Int = 0,
    val delete: Int = 0,
    val attributes: Attrs? = null
) {
    val isInsert: Boolean get() = insert != null
    val isDelete: Boolean get() = insert == null && delete > 0
    val isRetain: Boolean get() = insert == null && delete == 0
    val length: Int get() = when {
        insert != null -> insert.length
        delete > 0 -> delete
        else -> retain
    }
}

private class OpIter(private val ops: List<Op>) {
    var index = 0
    var offset = 0

    fun hasNext(): Boolean = peekLength() < INF
    fun peek(): Op? = ops.getOrNull(index)
    fun peekLength(): Int = if (index < ops.size) ops[index].length - offset else INF
    fun peekType(): Char {
        val op = peek() ?: return 'r'
        return when {
            op.isDelete -> 'd'
            op.isInsert -> 'i'
            else -> 'r'
        }
    }

    fun next(length: Int = INF): Op {
        if (index >= ops.size) return Op(retain = INF)
        val op = ops[index]
        val off = offset
        val olen = op.length
        var len = length
        if (len >= olen - off) {
            len = olen - off
            index += 1
            offset = 0
        } else {
            offset += len
        }
        return when {
            op.isDelete -> Op(delete = len)
            op.isInsert -> Op(insert = op.insert!!.substring(off, off + len), attributes = op.attributes)
            else -> Op(retain = len, attributes = op.attributes)
        }
    }
}

class Delta(initial: List<Op> = emptyList()) {
    private val list = ArrayList<Op>()
    val ops: List<Op> get() = list

    init { initial.forEach { push(it) } }

    fun push(raw: Op): Delta {
        val newOp = if (raw.attributes.isNullOrEmpty()) raw.copy(attributes = null) else raw
        if (newOp.length <= 0) return this
        var index = list.size
        var last: Op? = list.lastOrNull()
        if (last != null) {
            if (newOp.isDelete && last.isDelete) {
                list[index - 1] = Op(delete = last.delete + newOp.delete)
                return this
            }
            if (last.isDelete && newOp.isInsert) {
                index -= 1
                last = if (index > 0) list[index - 1] else null
                if (last == null) {
                    list.add(0, newOp)
                    return this
                }
            }
            if (newOp.attributes == last.attributes) {
                if (newOp.isInsert && last.isInsert) {
                    list[index - 1] = Op(insert = last.insert + newOp.insert, attributes = newOp.attributes)
                    return this
                }
                if (newOp.isRetain && last.isRetain) {
                    list[index - 1] = Op(retain = last.retain + newOp.retain, attributes = newOp.attributes)
                    return this
                }
            }
        }
        list.add(index, newOp)
        return this
    }

    fun retain(n: Int, attrs: Attrs? = null) = push(Op(retain = n, attributes = attrs))
    fun insert(text: String, attrs: Attrs? = null) = push(Op(insert = text, attributes = attrs))
    fun delete(n: Int) = push(Op(delete = n))

    fun chop(): Delta {
        val last = list.lastOrNull()
        if (last != null && last.isRetain && last.attributes == null) list.removeAt(list.size - 1)
        return this
    }

    fun length(): Int = list.sumOf { it.length }
    fun isEmpty(): Boolean = list.isEmpty()
    fun text(): String = buildString { list.forEach { if (it.insert != null) append(it.insert) } }

    fun compose(other: Delta): Delta {
        val a = OpIter(list)
        val b = OpIter(other.list)
        val out = Delta()
        while (a.hasNext() || b.hasNext()) {
            if (b.peekType() == 'i') {
                out.push(b.next())
            } else if (a.peekType() == 'd') {
                out.push(a.next())
            } else {
                val len = minOf(a.peekLength(), b.peekLength())
                val opA = a.next(len)
                val opB = b.next(len)
                if (opB.isRetain) {
                    val attrs = composeAttrs(opA.attributes, opB.attributes, opA.isRetain)
                    out.push(if (opA.isRetain) Op(retain = len, attributes = attrs) else Op(insert = opA.insert, attributes = attrs))
                } else if (opB.isDelete && opA.isRetain) {
                    out.push(opB)
                }
            }
        }
        return out.chop()
    }

    /** Transformiert [other] gegen dieses Delta. priority = true: dieses Delta war zuerst. */
    fun transform(other: Delta, priority: Boolean): Delta {
        val a = OpIter(list)
        val b = OpIter(other.list)
        val out = Delta()
        while (a.hasNext() || b.hasNext()) {
            if (a.peekType() == 'i' && (priority || b.peekType() != 'i')) {
                out.push(Op(retain = a.next().length))
            } else if (b.peekType() == 'i') {
                out.push(b.next())
            } else {
                val len = minOf(a.peekLength(), b.peekLength())
                val opA = a.next(len)
                val opB = b.next(len)
                if (opA.isDelete) continue
                if (opB.isDelete) out.push(opB)
                else out.push(Op(retain = len, attributes = transformAttrs(opA.attributes, opB.attributes, priority)))
            }
        }
        return out.chop()
    }

    fun transformPosition(index0: Int, priority: Boolean = false): Int {
        var index = index0
        val it = OpIter(list)
        var offset = 0
        while (it.hasNext() && offset <= index) {
            val len = it.peekLength()
            val type = it.peekType()
            it.next()
            if (type == 'd') {
                index -= minOf(len, index - offset)
                continue
            }
            if (type == 'i' && (offset < index || !priority)) index += len
            offset += len
        }
        return index
    }

    /** Teil eines Dokument-Deltas [start, end). */
    fun slice(start: Int, end: Int = INF): Delta {
        val out = Delta()
        val it = OpIter(list)
        var index = 0
        while (index < end && it.hasNext()) {
            val next: Op
            if (index < start) {
                next = it.next(start - index)
            } else {
                next = it.next(end - index)
                out.push(next)
            }
            index += next.length
        }
        return out
    }

    /** Umkehrung relativ zum Dokument [base] (Zustand vor diesem Delta). */
    fun invert(base: Delta): Delta {
        val out = Delta()
        var baseIndex = 0
        for (op in list) {
            when {
                op.isInsert -> out.delete(op.length)
                op.isRetain && op.attributes == null -> {
                    out.retain(op.retain)
                    baseIndex += op.retain
                }
                else -> {
                    val len = op.length
                    for (baseOp in base.slice(baseIndex, baseIndex + len).ops) {
                        if (op.isDelete) out.push(baseOp)
                        else out.retain(baseOp.length, invertAttrs(op.attributes ?: emptyMap(), baseOp.attributes ?: emptyMap()))
                    }
                    baseIndex += len
                }
            }
        }
        return out.chop()
    }

    fun copy(): Delta = Delta(list)

    override fun equals(other: Any?): Boolean = other is Delta && other.list == list
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = "Delta($list)"

    fun toJson(): JsonArray = JsonArray().also { arr -> list.forEach { arr.add(it.toJson()) } }

    companion object {
        fun plain(text: String): Delta = if (text.isEmpty()) Delta() else Delta().insert(text)
        fun emptyBody(): Delta = Delta().insert("\n")

        fun fromJson(element: JsonElement?): Delta {
            val out = Delta()
            if (element == null || !element.isJsonArray) return out
            for (e in element.asJsonArray) {
                if (!e.isJsonObject) continue
                val o = e.asJsonObject
                val attrs = parseAttrs(o.get("attributes"))
                when {
                    o.has("insert") && o.get("insert").isJsonPrimitive ->
                        out.push(Op(insert = o.get("insert").asString, attributes = attrs))
                    o.has("retain") -> out.push(Op(retain = o.get("retain").asInt, attributes = attrs))
                    o.has("delete") -> out.push(Op(delete = o.get("delete").asInt))
                }
            }
            return out
        }

        private fun parseAttrs(el: JsonElement?): Attrs? {
            if (el == null || !el.isJsonObject) return null
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in el.asJsonObject.entrySet()) {
                out[k] = when {
                    v == null || v.isJsonNull -> null
                    v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asNumber.toInt()
                    v.isJsonPrimitive -> v.asString
                    else -> null
                }
            }
            return out.ifEmpty { null }
        }
    }
}

fun Op.toJson(): JsonObject = JsonObject().apply {
    when {
        insert != null -> addProperty("insert", insert)
        delete > 0 -> addProperty("delete", delete)
        else -> addProperty("retain", retain)
    }
    attributes?.takeIf { it.isNotEmpty() }?.let { attrs ->
        add("attributes", JsonObject().apply {
            for ((k, v) in attrs) when (v) {
                null -> add(k, JsonNull.INSTANCE)
                is Boolean -> addProperty(k, v)
                is Number -> addProperty(k, v.toInt())
                else -> addProperty(k, v.toString())
            }
        })
    }
}

private fun composeAttrs(a: Attrs?, b: Attrs?, keepNull: Boolean): Attrs? {
    val aa = a ?: emptyMap()
    val bb = b ?: emptyMap()
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in bb) if (keepNull || v != null) out[k] = v
    for ((k, v) in aa) if (k !in bb) out[k] = v
    return out.ifEmpty { null }
}

private fun transformAttrs(a: Attrs?, b: Attrs?, priority: Boolean): Attrs? {
    if (a == null) return b
    if (b == null) return null
    if (!priority) return b
    val out = b.filterKeys { it !in a }
    return out.ifEmpty { null }
}

private fun invertAttrs(attr: Attrs, base: Attrs): Attrs {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in base) if (v != attr[k] && attr.containsKey(k)) out[k] = v
    for ((k, v) in attr) if (v != base[k] && !base.containsKey(k)) out[k] = null
    return out
}
