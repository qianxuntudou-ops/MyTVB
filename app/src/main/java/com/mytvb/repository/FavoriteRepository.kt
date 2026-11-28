package com.mytvb.repository

import com.mytvb.repository.remote.FavoriteRepository as NetworkFavoriteRepository

class FavoriteRepository(
    private val delegate: NetworkFavoriteRepository
) {
    suspend fun getFavoriteFolders(upMid: Long, rid: Long? = null) = delegate.getFavoriteFolders(upMid, rid)

    suspend fun getFavoriteFolderInfo(mediaId: Long) = delegate.getFavoriteFolderInfo(mediaId)

    suspend fun getFavoriteFolderDetail(mediaId: Long, page: Int, pageSize: Int) =
        delegate.getFavoriteFolderDetail(mediaId, page, pageSize)

    suspend fun addFavorite(rid: Long, addMediaIds: String) = delegate.addFavorite(rid, addMediaIds)

    suspend fun removeFavorite(rid: Long, delMediaIds: String) = delegate.removeFavorite(rid, delMediaIds)

    suspend fun updateFavorite(rid: Long, addMediaIds: String?, delMediaIds: String?) =
        delegate.updateFavorite(rid, addMediaIds, delMediaIds)
}
