package com.pinotrouge.messaging.ui.rules

/**
 * Move the element at [fromIndex] to [toIndex], shifting neighbours.
 * Returns the same list instance when indices are equal or out of range.
 */
internal fun <T> moveItem(list: List<T>, fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex) return list
    if (fromIndex !in list.indices || toIndex !in list.indices) return list
    val mutable = list.toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
