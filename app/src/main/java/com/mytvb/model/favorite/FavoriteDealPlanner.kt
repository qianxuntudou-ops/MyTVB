package com.mytvb.model.favorite

/**
 * 收藏（加入/移出收藏夹）请求规划。
 *
 * B 站 `x/v3/fav/resource/deal` 的 add_media_ids / del_media_ids 都支持逗号分隔的多个夹 id，
 * 网页端换夹就是「一次请求同时 add 新夹 + del 旧夹」。
 *
 * issue#156 的根因：旧实现把「用户点的那个夹」直接当作删除目标。
 * 视频在 A 夹时点 B 夹 → 发 del_media_ids=B；服务端对「删除一个本来就不在的收藏」幂等返回成功，
 * 客户端于是判定成功、把卡片从列表移除，但视频仍留在 A 夹。用户发现没换过去只能反复重试，
 * 同一 rid 短时间内连续 deal 正是触发收藏接口账号级风控（HTTP 412，约 1 小时解封）的直接原因。
 *
 * 现在按「视频实际所在夹（fav_state==1）」计算，保证 del 的永远是真实来源夹，
 * 换夹也只需一次请求，不再产生无效 deal。
 */
data class FavoriteDealPlan(
    val addMediaIds: String?,
    val delMediaIds: String?,
    /** 执行后该视频是否仍处于「已收藏」状态（决定菜单文案与列表是否移除卡片）。 */
    val favoritedAfter: Boolean
) {
    val isEmpty: Boolean
        get() = addMediaIds.isNullOrBlank() && delMediaIds.isNullOrBlank()
}

/**
 * @param folders 带 rid 查询得到的收藏夹列表（favState==1 表示该视频当前在这个夹里）
 * @param targetFolderId 用户点选的夹；传 null 表示「点了收藏按钮」这类无目标夹的切换操作
 */
fun planFavoriteDeal(
    folders: List<FavoriteFolderModel>,
    targetFolderId: Long? = null
): FavoriteDealPlan {
    val favoritedIds = folders.filter { it.favState == 1 }.map { it.id }.filter { it > 0L }.distinct()
    val target = targetFolderId?.takeIf { it > 0L }

    if (target == null) {
        // 收藏按钮切换：已收藏 → 从所有所在夹移除；未收藏 → 加入第一个夹（默认收藏夹）
        if (favoritedIds.isNotEmpty()) {
            return FavoriteDealPlan(
                addMediaIds = null,
                delMediaIds = favoritedIds.joinToString(","),
                favoritedAfter = false
            )
        }
        val fallback = folders.firstOrNull { it.id > 0L }?.id
            ?: return FavoriteDealPlan(null, null, favoritedAfter = false)
        return FavoriteDealPlan(
            addMediaIds = fallback.toString(),
            delMediaIds = null,
            favoritedAfter = true
        )
    }

    if (target in favoritedIds) {
        // 点的是已收藏的夹 → 只从这个夹移除；若同时在别的夹里，整体仍是已收藏
        return FavoriteDealPlan(
            addMediaIds = null,
            delMediaIds = target.toString(),
            favoritedAfter = favoritedIds.size > 1
        )
    }

    // 点的是未收藏的夹 → 加入目标夹，同时从其它已收藏夹移除（换夹，一次请求）
    val removeIds = favoritedIds.filter { it != target }
    return FavoriteDealPlan(
        addMediaIds = target.toString(),
        delMediaIds = removeIds.takeIf { it.isNotEmpty() }?.joinToString(","),
        favoritedAfter = true
    )
}
