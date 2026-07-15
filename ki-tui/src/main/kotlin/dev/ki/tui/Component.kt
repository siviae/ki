package dev.ki.tui

/**
 * A renderable UI element. The contract mirrors pi's TUI: [render] returns one
 * string per line, and **no returned line may exceed [width] display cells** — the
 * renderer treats an over-wide line as a bug. Use [Width.truncateToWidth] /
 * [Width.wrapText] to stay within budget.
 */
interface Component {
    /** Render to lines for the given viewport [width] (in terminal cells). */
    fun render(width: Int): List<String>

    /** Handle a raw input chunk while focused. Default: ignore. */
    fun handleInput(data: String) {}

    /** Drop any cached render state. Default: no-op. */
    fun invalidate() {}
}

/** A vertical stack of child components; renders their lines top-to-bottom. */
open class Container(children: List<Component> = emptyList()) : Component {
    private val children = ArrayList(children)

    fun add(child: Component) { children.add(child) }
    fun clear() { children.clear() }
    fun childCount(): Int = children.size

    override fun render(width: Int): List<String> {
        val out = ArrayList<String>()
        for (c in children) out.addAll(c.render(width))
        return out
    }

    override fun invalidate() { children.forEach { it.invalidate() } }
}

/**
 * Word-wrapped text block. Each rendered line is padded to full [width] so a
 * differential redraw fully overwrites whatever was there before. Blank text
 * renders as nothing (use [Spacer] for deliberate blank lines).
 */
open class Text(text: String = "", private val paddingX: Int = 0) : Component {
    private var text: String = text
    private var cache: List<String>? = null
    private var cacheWidth: Int = -1

    fun setText(value: String) {
        if (value != text) { text = value; cache = null }
    }

    override fun invalidate() { cache = null }

    override fun render(width: Int): List<String> {
        cache?.let { if (cacheWidth == width) return it }
        val lines = if (text.isBlank()) {
            emptyList()
        } else {
            val contentWidth = (width - paddingX * 2).coerceAtLeast(1)
            val pad = " ".repeat(paddingX)
            Width.wrapText(text.replace("\t", "   "), contentWidth)
                .map { Width.padTo(pad + it + pad, width) }
        }
        cache = lines
        cacheWidth = width
        return lines
    }
}

/** [count] blank lines. */
class Spacer(private val count: Int = 1) : Component {
    override fun render(width: Int): List<String> = List(count) { " ".repeat(width) }
}
