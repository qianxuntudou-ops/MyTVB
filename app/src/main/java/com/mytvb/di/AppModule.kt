package com.mytvb.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.mytvb.core.common.settings.AppSettingsDataStore
import com.mytvb.event.AppEventHub
import com.mytvb.network.NetworkManager
import com.mytvb.network.api.ApiService
import com.mytvb.network.security.NetworkManagerSecurityGateway
import com.mytvb.network.security.NetworkManagerWebGateway
import com.mytvb.network.security.NetworkSecurityGateway
import com.mytvb.network.security.NetworkWebGateway
import com.mytvb.network.session.NetworkManagerSessionGateway
import com.mytvb.network.session.NetworkSessionGateway
import com.mytvb.network.session.SessionStateRepository
import com.mytvb.repository.AllSeriesRepository
import com.mytvb.repository.AuthRepository
import com.mytvb.repository.FavoriteRepository
import com.mytvb.repository.HomeLaneRepository
import com.mytvb.repository.PersonalFeedPrewarmer
import com.mytvb.repository.LiveRepository
import com.mytvb.repository.SearchRepository
import com.mytvb.repository.SeriesRepository
import com.mytvb.repository.UserRepository
import com.mytvb.repository.VideoRepository
import com.mytvb.feature.category.CategoryFeedRepository
import com.mytvb.feature.category.CategoryViewModel
import com.mytvb.feature.dynamic.DynamicViewModel
import com.mytvb.feature.home.HotViewModel
import com.mytvb.feature.home.HotFeedRepository
import com.mytvb.feature.home.HomeLaneFeedRepository
import com.mytvb.feature.home.HomeLaneViewModel
import com.mytvb.feature.home.RecommendDislikeFeedback
import com.mytvb.feature.home.RecommendFeedRepository
import com.mytvb.feature.home.RecommendViewModel
import com.mytvb.feature.live.LiveListViewModel
import com.mytvb.feature.live.LiveRecommendViewModel
import com.mytvb.feature.live.LiveViewModel
import com.mytvb.feature.me.MeListViewModel
import com.mytvb.feature.me.MeViewModel
import com.mytvb.feature.search.SearchViewModel
import com.mytvb.feature.player.LivePlayerViewModel
import com.mytvb.feature.player.danmaku.LiveDanmakuManager
import com.mytvb.feature.player.douyin.DouyinModeManager
import com.mytvb.ui.fragment.main.MainNavigationViewModel
import com.mytvb.feature.player.VideoPlayerViewModel
import com.mytvb.feature.series.SeriesDetailViewModel
import com.mytvb.network.cookie.CookieManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin DI 延迟加载检查结论：
// Koin 的 single {} 和 viewModel {} 默认都是延迟创建的（首次请求时才实例化），
// 当前模块声明方式已经是最佳实践，无需额外优化。
val networkModule = module {
    single<ApiService> { NetworkManager.apiService }
    single<OkHttpClient> { NetworkManager.getOkHttpClient() }
    single<CookieManager> { NetworkManager.getCookieManager() }
    // 同一实例绑定双接口：Repository/播放内核用 NetworkSessionGateway，UI 层只注入 SessionStateRepository
    single<NetworkSessionGateway> { NetworkManagerSessionGateway() }
    single<SessionStateRepository> { get<NetworkSessionGateway>() as SessionStateRepository }
    single<NetworkSecurityGateway> { NetworkManagerSecurityGateway() }
    single<NetworkWebGateway> { NetworkManagerWebGateway() }
    factory(named("noCookie")) { NetworkManager.noCookieApiService }
}

val repositoryModule = module {
    single { com.mytvb.repository.remote.AllSeriesRepository(get()) }
    single { com.mytvb.repository.remote.AuthRepository(get()) }
    single { com.mytvb.repository.remote.TvAuthRepository(get(named("noCookie"))) }
    single { com.mytvb.repository.remote.FavoriteRepository(get(), get(), get()) }
    single { com.mytvb.repository.remote.HomeLaneRepository(get(), get(), get(), get()) }
    single { com.mytvb.repository.remote.LiveRepository(get(), get()) }
    single { com.mytvb.repository.remote.SearchRepository(get(), get()) }
    single { com.mytvb.repository.remote.SeriesRepository(get(), get(), get()) }
    single { com.mytvb.repository.remote.VideoRepository(get(), get(), get()) }
    single { AllSeriesRepository(get()) }
    single { AuthRepository(get()) }
    single { FavoriteRepository(get()) }
    single { HomeLaneRepository(get()) }
    single { LiveRepository(get()) }
    single { SearchRepository(get()) }
    single { SeriesRepository(get()) }
    single { VideoRepository(get(), get()) }
    single { UserRepository(get(), get(), get()) }
    single { PersonalFeedPrewarmer(get(), get(), get()) }
    single { RecommendFeedRepository(androidContext()) }
    single { HotFeedRepository() }
    single { HomeLaneFeedRepository(get()) }
    single { CategoryFeedRepository() }
}

@OptIn(UnstableApi::class)
val viewModelModule = module {
    single { RecommendDislikeFeedback(get(), get()) }
    viewModel { RecommendViewModel(get(), get(), androidContext()) }
    viewModel { HotViewModel(get(), get(), androidContext()) }
    viewModel { (type: Int) -> HomeLaneViewModel(type, get()) }
    viewModel { MainNavigationViewModel(get()) }
    viewModel { VideoPlayerViewModel(get(), get(), get(), get(), get(), get(), get(named("noCookie")), get(), androidContext(), get()) }
    viewModel { CategoryViewModel(get()) }
    viewModel { DynamicViewModel(get()) }
    viewModel { LiveViewModel(get(), androidContext()) }
    viewModel { LiveListViewModel(get()) }
    viewModel { LiveRecommendViewModel(get()) }
    viewModel { MeListViewModel(get(), get()) }
    viewModel { MeViewModel(get(), get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { LivePlayerViewModel(get(), LiveDanmakuManager(get(), get(), get()), androidContext()) }
    viewModel { SeriesDetailViewModel(get(), get()) }
}

val eventModule = module {
    single { AppEventHub() }
    single { AppSettingsDataStore(androidContext()) }
    single { DouyinModeManager(androidContext(), get(), get()) }
}

val appModules = listOf(
    networkModule,
    repositoryModule,
    eventModule,
    viewModelModule
)
