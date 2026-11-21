package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.model.RawNode

/**
 * className + 节点属性 → 固定角色词表。
 *
 * 为什么要「类名 + 属性」两路：原生 View 的 className 很可靠（Button/EditText…），
 * 但 Compose 和 WebView 里大量节点的类名是 `android.view.View` 或 `android.widget.TextView`，
 * 角色信息只能从 clickable/checkable/editable 等属性推断。所以先按类名精确映射，
 * 映射不出来再按属性兜底，最后才是 OTHER。
 *
 * 匹配用类名的**简单名**（最后一个 '.' 之后），这样 `androidx.appcompat.widget.AppCompatButton`、
 * `com.google.android.material.button.MaterialButton` 都能落到「按钮」。
 */
object RoleMapper {

    /** 简单类名精确映射。顺序无关。 */
    private val exact: Map<String, Role> = buildMap {
        fun put(role: Role, vararg names: String) { for (n in names) this[n] = role }
        put(Role.BUTTON, "Button", "ImageButton", "AppCompatButton", "AppCompatImageButton", "MaterialButton",
            "FloatingActionButton", "ExtendedFloatingActionButton", "Chip", "ToggleButton", "MaterialButtonToggleGroup",
            "CompoundButton")
        put(Role.INPUT, "EditText", "AppCompatEditText", "TextInputEditText", "AutoCompleteTextView",
            "AppCompatAutoCompleteTextView", "MultiAutoCompleteTextView", "MaterialAutoCompleteTextView",
            "SearchView", "SearchAutoComplete", "ExtractEditText", "TextField", "BasicTextField")
        put(Role.SWITCH, "Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch")
        put(Role.CHECKBOX, "CheckBox", "AppCompatCheckBox", "MaterialCheckBox", "CheckedTextView", "AppCompatCheckedTextView")
        put(Role.RADIO, "RadioButton", "AppCompatRadioButton", "MaterialRadioButton")
        put(Role.LIST, "RecyclerView", "ListView", "ExpandableListView", "ScrollView", "NestedScrollView",
            "HorizontalScrollView", "ViewPager", "ViewPager2", "AbsListView", "WearableRecyclerView",
            "LazyColumn", "LazyRow", "LazyList")
        put(Role.GRID, "GridView", "LazyVerticalGrid", "LazyHorizontalGrid", "StaggeredGridView", "TableLayout")
        put(Role.TAB, "TabWidget", "TabLayout", "TabView", "TabLayout\$TabView", "BottomNavigationItemView",
            "NavigationBarItemView", "NavigationRailItemView", "Tab")
        put(Role.IMAGE, "ImageView", "AppCompatImageView", "ShapeableImageView", "QuickContactBadge", "Image")
        put(Role.SLIDER, "SeekBar", "AppCompatSeekBar", "RatingBar", "AppCompatRatingBar", "Slider", "RangeSlider",
            "AbsSeekBar", "NumberPicker")
        put(Role.PROGRESS, "ProgressBar", "ContentLoadingProgressBar", "CircularProgressIndicator",
            "LinearProgressIndicator")
        put(Role.MENUITEM, "ActionMenuItemView", "ListMenuItemView", "MenuItem", "NavigationMenuItemView",
            "DropdownMenuItem", "MenuPopupWindow\$MenuDropDownListView")
        put(Role.TEXT, "TextView", "AppCompatTextView", "MaterialTextView", "TextClock", "Chronometer",
            "TextSwitcher", "Text")
        put(Role.LINK, "Link", "URLSpan", "ClickableSpan")
    }

    /**
     * 映射一个节点。[node] 的 checkable/editable/scrollable/clickable 等属性只在类名
     * 不足以判断时参与（见类注释）。
     */
    fun map(node: RawNode): Role = map(
        node.className, clickable = node.clickable || node.longClickable, editable = node.editable,
        checkable = node.checkable, scrollable = node.scrollable, collectionColumns = node.collectionColumns,
        hasText = !node.text.isNullOrBlank(), viewId = node.viewId,
    )

    /** 不依赖 RawNode 的版本，便于单测和夹具里的自定义节点。 */
    fun map(
        className: String?,
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        scrollable: Boolean = false,
        collectionColumns: Int = -1,
        hasText: Boolean = false,
        viewId: String? = null,
    ): Role {
        // 可编辑属性最可信：不管类名是什么（自定义输入框、WebView 的 <input>），都是输入框。
        if (editable) return Role.INPUT
        val simple = simpleName(className)
        exact[simple]?.let { role ->
            // 有些「列表」类名其实是网格（RecyclerView + GridLayoutManager 报多列）。
            if (role == Role.LIST && collectionColumns > 1) return Role.GRID
            // CompoundButton 可勾选时更像复选框。
            if (simple == "CompoundButton" && checkable) return Role.CHECKBOX
            return role
        }
        // 后缀匹配：厂商自定义控件常以标准控件名结尾（如 MiuiSwitch、HwButton、OplusEditText）。
        suffixRole(simple)?.let { return if (it == Role.LIST && collectionColumns > 1) Role.GRID else it }
        // WebView 特殊类名（Chromium 会报这些）。
        when (simple) {
            "WebView" -> return Role.OTHER
        }
        // 属性兜底：Compose / WebView 节点大多走这里。
        if (checkable) return Role.CHECKBOX
        if (scrollable) return if (collectionColumns > 1) Role.GRID else Role.LIST
        val tail = ObservedElement.viewIdTail(viewId)?.lowercase()
        if (tail != null) {
            if ("tab" in tail) return Role.TAB
            if ("link" in tail || "url" in tail) return Role.LINK
        }
        if (clickable) return Role.BUTTON
        if (hasText) return Role.TEXT
        return Role.OTHER
    }

    /** 后缀表：顺序很重要，长的、更具体的在前（EditText 先于 Text，ImageButton 先于 Button）。 */
    private val suffixes: List<Pair<String, Role>> = listOf(
        "EditText" to Role.INPUT,
        "TextField" to Role.INPUT,
        "ImageButton" to Role.BUTTON,
        "RadioButton" to Role.RADIO,
        "CheckBox" to Role.CHECKBOX,
        "Checkbox" to Role.CHECKBOX,
        "Switch" to Role.SWITCH,
        "SwitchCompat" to Role.SWITCH,
        "ToggleButton" to Role.SWITCH,
        "Button" to Role.BUTTON,
        "SeekBar" to Role.SLIDER,
        "Slider" to Role.SLIDER,
        "ProgressBar" to Role.PROGRESS,
        "GridView" to Role.GRID,
        "RecyclerView" to Role.LIST,
        "ListView" to Role.LIST,
        "ScrollView" to Role.LIST,
        "ViewPager" to Role.LIST,
        "TabView" to Role.TAB,
        "TabLayout" to Role.TAB,
        "ImageView" to Role.IMAGE,
        "TextView" to Role.TEXT,
    )

    private fun suffixRole(simple: String): Role? {
        if (simple.isEmpty()) return null
        for ((suf, role) in suffixes) if (simple.endsWith(suf)) return role
        return null
    }

    /** 类名的简单名：去掉包名，保留内部类的 `$` 部分（TabLayout$TabView）。 */
    fun simpleName(className: String?): String {
        if (className.isNullOrBlank()) return ""
        val dot = className.lastIndexOf('.')
        return if (dot >= 0) className.substring(dot + 1) else className
    }

    /** 是否是 WebView 容器（ObservationBuilder 对 WebView 内部不做布局折叠的特殊处理用）。 */
    fun isWebView(className: String?): Boolean = simpleName(className).let { it == "WebView" || it.endsWith("WebView") }

    /**
     * 是否是纯布局类（FrameLayout/LinearLayout/ConstraintLayout/View/ViewGroup…）。
     * ObservationBuilder 用它判断「没有任何语义的容器」以便折叠。
     */
    fun isLayout(className: String?): Boolean {
        val s = simpleName(className)
        if (s.isEmpty()) return true
        return s in layoutNames || s.endsWith("Layout") || s == "View" || s == "ViewGroup"
    }

    private val layoutNames = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout", "CoordinatorLayout",
        "MotionLayout", "GridLayout", "AppBarLayout", "CollapsingToolbarLayout", "DrawerLayout",
        "SwipeRefreshLayout", "CardView", "MaterialCardView", "Toolbar", "ActionBarContainer",
        "ActionBarOverlayLayout", "ContentFrameLayout", "FitWindowsLinearLayout", "DecorView",
        "ComposeView", "AndroidComposeView", "ViewStub", "Space", "LinearLayoutCompat",
    )
}
