package com.futo.platformplayer.fragment.mainactivity.main

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.futo.platformplayer.*
import com.futo.platformplayer.api.media.models.contents.ContentType
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.video.IPlatformVideo
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.api.media.structures.EmptyPager
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.cache.ChannelContentCache
import com.futo.platformplayer.constructs.TaskHandler
import com.futo.platformplayer.engine.exceptions.PluginException
import com.futo.platformplayer.exceptions.ChannelException
import com.futo.platformplayer.exceptions.RateLimitException
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StateSubscriptions
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.FragmentedStorageFileJson
import com.futo.platformplayer.views.announcements.AnnouncementView
import com.futo.platformplayer.views.FeedStyle
import com.futo.platformplayer.views.adapters.ContentPreviewViewHolder
import com.futo.platformplayer.views.adapters.InsertedViewAdapterWithLoader
import com.futo.platformplayer.views.adapters.InsertedViewHolder
import com.futo.platformplayer.views.subscriptions.SubscriptionBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

class SubscriptionsFeedFragment : MainFragment() {
    override val isMainView : Boolean = true;
    override val isTab: Boolean = true;
    override val hasBottomBar: Boolean get() = true;

    private var _view: SubscriptionsFeedView? = null;
    private var _cachedRecyclerData: FeedView.RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, LinearLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>? = null;

    override fun onShownWithView(parameter: Any?, isBack: Boolean) {
        super.onShownWithView(parameter, isBack);
        _view?.onShown();
    }

    override fun onResume() {
        super.onResume()
        _view?.onResume();
    }

    override fun onPause() {
        super.onPause()
        _view?.onPause();
    }

    override fun onCreateMainView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = SubscriptionsFeedView(this, inflater, _cachedRecyclerData);
        _view = view;
        return view;
    }

    override fun onDestroyMainView() {
        super.onDestroyMainView();
        val view = _view;
        if (view != null) {
            _cachedRecyclerData = view.recyclerData;
            view.cleanup();
            _view = null;
        }
    }

    fun setPreviewsEnabled(previewsEnabled: Boolean) {
        _view?.setPreviewsEnabled(previewsEnabled && Settings.instance.subscriptions.previewFeedItems);
    }

    @SuppressLint("ViewConstructor")
    class SubscriptionsFeedView : ContentFeedView<SubscriptionsFeedFragment> {
        constructor(fragment: SubscriptionsFeedFragment, inflater: LayoutInflater, cachedRecyclerData: RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, LinearLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>? = null) : super(fragment, inflater, cachedRecyclerData) {
            Logger.i(TAG, "SubscriptionsFeedFragment constructor()");
            StateSubscriptions.instance.onGlobalSubscriptionsUpdateProgress.subscribe(this) { progress, total ->
                fragment.lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        setProgress(progress, total);
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to set progress", e);
                    }
                }
            };

            StateSubscriptions.instance.onSubscriptionsChanged.subscribe(this) { subs, added ->
                if(!added)
                    StateSubscriptions.instance.clearSubscriptionFeed();
                StateApp.instance.scopeOrNull?.let {
                    StateSubscriptions.instance.updateSubscriptionFeed(it);
                }
                recyclerData.lastLoad = OffsetDateTime.MIN;
            };

            initializeToolbarContent();

            setPreviewsEnabled(Settings.instance.subscriptions.previewFeedItems);
        }

        fun onShown() {
            Logger.i(TAG, "SubscriptionsFeedFragment onShown()");
            val currentProgress = StateSubscriptions.instance.getGlobalSubscriptionProgress();
            setProgress(currentProgress.first, currentProgress.second);

            if(recyclerData.loadedFeedStyle != feedStyle ||
                recyclerData.lastLoad.getNowDiffSeconds() > 60 ) {
                recyclerData.lastLoad = OffsetDateTime.now();

                if(StateSubscriptions.instance.getOldestUpdateTime().getNowDiffMinutes() > 5 && Settings.instance.subscriptions.fetchOnTabOpen)
                    loadResults(false);
                else if(recyclerData.results.size == 0)
                    loadCache();
            }

            val announcementsView = _announcementsView;
            val homeTab = Settings.instance.tabs.find { it.id == 0 };
            val isHomeEnabled = homeTab?.enabled == true;
            if (announcementsView != null && isHomeEnabled) {
                headerView?.removeView(announcementsView);
                _announcementsView = null;
            }

            if (announcementsView == null && !isHomeEnabled) {
                val c = context;
                if (c != null) {
                    _announcementsView = AnnouncementView(c, null).apply {
                        headerView?.addView(this)
                    };
                }
            }

            if (!StateSubscriptions.instance.isGlobalUpdating) {
                finishRefreshLayoutLoader();
            }
        }

        override fun cleanup() {
            super.cleanup()
            StateSubscriptions.instance.onGlobalSubscriptionsUpdateProgress.remove(this);
            StateSubscriptions.instance.onSubscriptionsChanged.remove(this);
        }

        override val feedStyle: FeedStyle get() = Settings.instance.subscriptions.getSubscriptionsFeedStyle();

        private var _subscriptionBar: SubscriptionBar? = null;

        private var _announcementsView: AnnouncementView? = null;

        @Serializable
        class FeedFilterSettings: FragmentedStorageFileJson() {
            val allowContentTypes: MutableList<ContentType> = mutableListOf(ContentType.MEDIA, ContentType.POST);
            var allowLive: Boolean = true;
            var allowPlanned: Boolean = false;
            override fun encode(): String {
                return Json.encodeToString(this);
            }
        }
        private val _filterLock = Object();
        private val _filterSettings = FragmentedStorage.get<FeedFilterSettings>("subFeedFilter");

        private var _bypassRateLimit = false;
        private val _lastExceptions: List<Throwable>? = null;
        private val _taskGetPager = TaskHandler<Boolean, IPager<IPlatformContent>>({StateApp.instance.scope}, { withRefresh ->
            if(!_bypassRateLimit) {
                val subRequestCounts = StateSubscriptions.instance.getSubscriptionRequestCount();
                val reqCountStr = subRequestCounts.map { "    ${it.key.config.name}: ${it.value}/${it.key.getSubscriptionRateLimit()}" }.joinToString("\n");
                val rateLimitPlugins = subRequestCounts.filter { clientCount -> clientCount.key.getSubscriptionRateLimit()?.let { rateLimit -> clientCount.value > rateLimit } == true }
                Logger.w(TAG, "Trying to refreshing subscriptions with requests:\n" + reqCountStr);
                if(rateLimitPlugins.any())
                    throw RateLimitException(rateLimitPlugins.map { it.key.id });
            }
            _bypassRateLimit = false;
            val resp = StateSubscriptions.instance.getGlobalSubscriptionFeed(StateApp.instance.scope, withRefresh);

            val currentExs = StateSubscriptions.instance.globalSubscriptionExceptions;
            if(currentExs != _lastExceptions && currentExs.any())
                handleExceptions(currentExs);

            return@TaskHandler resp;
        })
            .success {
                if(!Settings.instance.subscriptions.alwaysReloadFromCache)
                    loadedResult(it);
                else {
                    finishRefreshLayoutLoader();
                    setLoading(false);
                    loadCache();
                }
            } //TODO: Remove
            .exception<RateLimitException> {
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val subs = StateSubscriptions.instance.getSubscriptions();
                    val subsByLimited = it.pluginIds.map{ StatePlatform.instance.getClientOrNull(it) }
                        .filterIsInstance<JSClient>()
                        .associateWith { client -> subs.filter { it.getClient() == client } }
                        .map { Pair(it.key, it.value) }

                    withContext(Dispatchers.Main) {
                        UIDialogs.showDialog(context, R.drawable.ic_security_pred,
                            context.getString(R.string.rate_limit_warning),  context.getString(R.string.this_is_a_temporary_measure_to_prevent_people_from_hitting_rate_limit_until_we_have_better_support_for_lots_of_subscriptions) + context.getString(R.string.you_have_too_many_subscriptions_for_the_following_plugins),
                            subsByLimited.map { it.first.config.name + ": " + it.second.size + " " + context.getString(R.string.subscriptions) } .joinToString("\n"), 0, UIDialogs.Action("Refresh Anyway", {
                                _bypassRateLimit = true;
                                loadResults(true);
                            }, UIDialogs.ActionStyle.DANGEROUS_TEXT),
                            UIDialogs.Action("OK", {
                                finishRefreshLayoutLoader();
                                setLoading(false);
                            }, UIDialogs.ActionStyle.PRIMARY));
                    }
                }
            }
            .exception<Throwable> {
                Logger.w(ChannelFragment.TAG, "Failed to load channel.", it);
                if(it !is CancellationException)
                    UIDialogs.showGeneralRetryErrorDialog(context, it.message ?: "", it, { loadResults(true) });
                else {
                    finishRefreshLayoutLoader();
                    setLoading(false);
                }
            };

        private fun initializeToolbarContent() {
            _subscriptionBar = SubscriptionBar(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            };
            _subscriptionBar?.onClickChannel?.subscribe { c -> fragment.navigate<ChannelFragment>(c); };

            synchronized(_filterLock) {
                _subscriptionBar?.setToggles(
                    SubscriptionBar.Toggle(context.getString(R.string.videos), _filterSettings.allowContentTypes.contains(ContentType.MEDIA)) { toggleFilterContentTypes(listOf(ContentType.MEDIA, ContentType.NESTED_VIDEO), it);  },
                    SubscriptionBar.Toggle(context.getString(R.string.posts),  _filterSettings.allowContentTypes.contains(ContentType.POST)) { toggleFilterContentType(ContentType.POST, it); },
                    SubscriptionBar.Toggle(context.getString(R.string.live), _filterSettings.allowLive) { _filterSettings.allowLive = it; _filterSettings.save(); loadResults(false); },
                    SubscriptionBar.Toggle(context.getString(R.string.planned), _filterSettings.allowPlanned) { _filterSettings.allowPlanned = it; _filterSettings.save(); loadResults(false); }
                );
            }

            _toolbarContentView.addView(_subscriptionBar, 0);
        }
        private fun toggleFilterContentTypes(contentTypes: List<ContentType>, isTrue: Boolean) {
            for(contentType in contentTypes)
                toggleFilterContentType(contentType, isTrue);
        }
        private fun toggleFilterContentType(contentType: ContentType, isTrue: Boolean) {
            synchronized(_filterLock) {
                if(!isTrue)
                    _filterSettings.allowContentTypes.remove(contentType);
                else if(!_filterSettings.allowContentTypes.contains(contentType))
                    _filterSettings.allowContentTypes.add(contentType)
                else null;
                _filterSettings.save();
            };
            if(Settings.instance.subscriptions.fetchOnTabOpen) //TODO: Do this different, temporary workaround
                loadResults(false);
            else
                loadCache();
        }

        override fun filterResults(results: List<IPlatformContent>): List<IPlatformContent> {
            val nowSoon = OffsetDateTime.now().plusMinutes(5);
            return results.filter {
                val allowedContentType = _filterSettings.allowContentTypes.contains(if(it.contentType == ContentType.NESTED_VIDEO) ContentType.MEDIA else it.contentType);

                if(it.datetime?.isAfter(nowSoon) == true) {
                    if(!_filterSettings.allowPlanned)
                        return@filter false;
                }

                if(_filterSettings.allowLive) { //If allowLive, always show live
                    if(it is IPlatformVideo && it.isLive)
                        return@filter true;
                }
                else if(it is IPlatformVideo && it.isLive)
                    return@filter false;

                return@filter allowedContentType;
            };
        }

        override fun reload() {
            loadResults(true);
        }


        private fun loadCache() {
            Logger.i(TAG, "Subscriptions load cache");
            val cachePager = ChannelContentCache.instance.getSubscriptionCachePager();
            val results = cachePager.getResults();
            Logger.i(TAG, "Subscriptions show cache (${results.size})");
            setTextCentered(if (results.isEmpty()) context.getString(R.string.no_results_found_swipe_down_to_refresh) else null);
            setPager(cachePager);
        }
        private fun loadResults(withRefetch: Boolean = false) {
            setLoading(true);
            Logger.i(TAG, "Subscriptions load");
            if(recyclerData.results.size == 0) {
                loadCache();
            } else {
                setTextCentered(null);
            }
            _taskGetPager.run(withRefetch);
        }

        override fun onRestoreCachedData(cachedData: RecyclerData<InsertedViewAdapterWithLoader<ContentPreviewViewHolder>, LinearLayoutManager, IPager<IPlatformContent>, IPlatformContent, IPlatformContent, InsertedViewHolder<ContentPreviewViewHolder>>) {
            super.onRestoreCachedData(cachedData);
            setTextCentered(if (cachedData.results.isEmpty()) context.getString(R.string.no_results_found_swipe_down_to_refresh) else null);
        }
        private fun loadedResult(pager: IPager<IPlatformContent>) {
            Logger.i(TAG, "Subscriptions new pager loaded (${pager.getResults().size})");

            fragment.lifecycleScope.launch(Dispatchers.Main) {
                try {
                    finishRefreshLayoutLoader();
                    setLoading(false);
                    setPager(pager);
                    setTextCentered(if (pager.getResults().isEmpty()) context.getString(R.string.no_results_found_swipe_down_to_refresh) else null);
                } catch (e: Throwable) {
                    Logger.e(TAG, "Failed to finish loading", e)
                }
            }/*.invokeOnCompletion { //Commented for now, because it doesn't fix the bug it was intended to fix, but might want it later anyway
                if(it is CancellationException) {
                    setLoading(false);
                }
            }*/
        }

        private fun handleExceptions(exs: List<Throwable>) {
            context?.let {
                fragment.lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        if (exs!!.size <= 8) {
                            for (ex in exs) {
                                var toShow = ex;
                                var channel: String? = null;
                                if (toShow is ChannelException) {
                                    channel = toShow.channelNameOrUrl;
                                    toShow = toShow.cause!!;
                                }
                                Logger.e(TAG, "Channel [${channel}] failed", ex);
                                if (toShow is PluginException)
                                    UIDialogs.toast(
                                        it,
                                        context.getString(R.string.plugin_pluginname_failed_message).replace("{pluginName}", toShow.config.name).replace("{message}", toShow.message ?: "")
                                    );
                                else
                                    UIDialogs.toast(it, ex.message ?: "");
                            }
                        }
                        else {
                            val failedPlugins = exs.filter { it is PluginException || (it is ChannelException && it.cause is PluginException) }
                                .map { if(it is ChannelException) (it.cause as PluginException) else if(it is PluginException) it else null  }
                                .filter { it != null }
                                .distinctBy { it?.config?.name }
                                .map { it!! }
                                .toList();
                            for(distinctPluginFail in failedPlugins)
                                UIDialogs.toast(it, context.getString(R.string.plugin_pluginname_failed_message).replace("{pluginName}", distinctPluginFail.config.name).replace("{message}", distinctPluginFail.message ?: ""));
                        }
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Failed to handle exceptions", e)
                    }
                }
            }
        }
    }

    companion object {
        val TAG = "SubscriptionsFeedFragment";

        fun newInstance() = SubscriptionsFeedFragment().apply {}
    }
}