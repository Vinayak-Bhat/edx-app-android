package org.edx.mobile.view;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.inject.Inject;
import com.joanzapata.iconify.IconDrawable;
import com.joanzapata.iconify.fonts.FontAwesomeIcons;

import org.edx.mobile.R;
import org.edx.mobile.base.BaseFragment;
import org.edx.mobile.core.IEdxEnvironment;
import org.edx.mobile.databinding.FragmentCourseTabsDashboardBinding;
import org.edx.mobile.logger.Logger;
import org.edx.mobile.model.FragmentItemModel;
import org.edx.mobile.model.api.EnrolledCoursesResponse;
import org.edx.mobile.module.analytics.AnalyticsRegistry;
import org.edx.mobile.module.db.DataCallback;
import org.edx.mobile.util.FileUtil;
import org.edx.mobile.util.NetworkUtil;
import org.edx.mobile.util.ResourceUtil;
import org.edx.mobile.util.images.ShareUtils;
import org.edx.mobile.view.adapters.FragmentItemPagerAdapter;
import org.edx.mobile.view.custom.ProgressWheel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import roboguice.inject.InjectExtra;

public class CourseTabsDashboardFragment extends BaseFragment {
    protected final Logger logger = new Logger(getClass().getName());

    private FragmentCourseTabsDashboardBinding binding;

    @Inject
    private IEdxEnvironment environment;

    @InjectExtra(Router.EXTRA_COURSE_DATA)
    private EnrolledCoursesResponse courseData;

    @Inject
    private AnalyticsRegistry analyticsRegistry;

    private ProgressWheel progressWheel;
    private MenuItem progressMenuItem;
    final private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateDownloadProgressRunnable;

    @NonNull
    public static CourseTabsDashboardFragment newInstance() {
        return new CourseTabsDashboardFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActivity().setTitle(courseData.getCourse().getName());
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.course_dashboard_menu, menu);
        if (environment.getConfig().isCourseSharingEnabled()) {
            menu.findItem(R.id.menu_item_share).setVisible(true);
        } else {
            menu.findItem(R.id.menu_item_share).setVisible(false);
        }
        handleDownloadProgressMenuItem(menu);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_course_tabs_dashboard, container, false);
        initializeTabs();
        return binding.getRoot();
    }

    public void initializeTabs() {
        // Get Frags list
        final List<FragmentItemModel> fragmentItems = getTabFragments();
        // Init tabs
        final TabLayout tabLayout = binding.tabLayout;
        TabLayout.Tab tab;
        for (FragmentItemModel fragmentItem : fragmentItems) {
            tab = tabLayout.newTab();
            IconDrawable iconDrawable = new IconDrawable(getContext(), fragmentItem.getIcon());
            iconDrawable.colorRes(getContext(), R.color.edx_brand_primary_base);
            tab.setIcon(iconDrawable);
            tab.setContentDescription(fragmentItem.getTitle());
            tabLayout.addTab(tab);
        }
        // Init view pager
        final FragmentItemPagerAdapter adapter = new FragmentItemPagerAdapter(this.getActivity().getSupportFragmentManager(), fragmentItems);
        binding.viewPager.setAdapter(adapter);
        binding.viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout) {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                getActivity().setTitle(fragmentItems.get(position).getTitle());
            }
        });
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                binding.viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_share:
                ShareUtils.showCourseShareMenu(getActivity(), getActivity().findViewById(R.id.menu_item_share),
                        courseData, analyticsRegistry, environment);
                //TODO: Remove this after testing
                environment.getRouter().showCourseDashboard(getActivity(), courseData, false);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (updateDownloadProgressRunnable != null) {
            updateDownloadProgressRunnable.run();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (updateDownloadProgressRunnable != null) {
            handler.removeCallbacks(updateDownloadProgressRunnable);
        }
    }

    public void handleDownloadProgressMenuItem(Menu menu) {
        MenuItem newProgressMenuItem = menu.findItem(R.id.menu_item_download_progress);
        View progressView = newProgressMenuItem.getActionView();
        ProgressWheel newProgressWheel = (ProgressWheel)
                progressView.findViewById(R.id.progress_wheel);
        if (progressMenuItem != null) {
            newProgressMenuItem.setVisible(progressMenuItem.isVisible());
            newProgressWheel.setProgress(progressWheel.getProgress());
        }
        progressMenuItem = newProgressMenuItem;
        progressWheel = newProgressWheel;
        progressView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                environment.getRouter().showDownloads(getActivity());
            }
        });
        if (updateDownloadProgressRunnable == null) {
            updateDownloadProgressRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!NetworkUtil.isConnected(getContext()) ||
                            !environment.getDatabase().isAnyVideoDownloading(null)) {
                        progressMenuItem.setVisible(false);
                    } else {
                        progressMenuItem.setVisible(true);
                        environment.getStorage().getAverageDownloadProgress(
                                new DataCallback<Integer>() {
                                    @Override
                                    public void onResult(Integer result) {
                                        int progressPercent = result;
                                        if (progressPercent >= 0 && progressPercent <= 100) {
                                            progressWheel.setProgressPercent(progressPercent);
                                        }
                                    }

                                    @Override
                                    public void onFail(Exception ex) {
                                        logger.error(ex);
                                    }
                                });
                    }
                    handler.postDelayed(this, DateUtils.SECOND_IN_MILLIS);
                }
            };
            updateDownloadProgressRunnable.run();
        }
    }

    public List<FragmentItemModel> getTabFragments() {
        ArrayList<FragmentItemModel> fragments = new ArrayList<>();
        fragments.add(new FragmentItemModel(new TestFragment(), courseData.getCourse().getName(), FontAwesomeIcons.fa_list_alt));
        if (environment.getConfig().isCourseVideosEnabled()) {
            fragments.add(new FragmentItemModel(new TestFragment(),
                    getResources().getString(R.string.videos_title), FontAwesomeIcons.fa_film));
        }
        if (environment.getConfig().isDiscussionsEnabled() &&
                !TextUtils.isEmpty(courseData.getCourse().getDiscussionUrl())) {
            fragments.add(new FragmentItemModel(CourseDiscussionTopicsFragment.newInstance(),
                    getResources().getString(R.string.discussion_title), FontAwesomeIcons.fa_comments_o));
        }
        if (environment.getConfig().isCourseDatesEnabled()) {
            fragments.add(new FragmentItemModel(getCourseDatesFragment(),
                    getResources().getString(R.string.course_dates_title), FontAwesomeIcons.fa_calendar));
        }
        fragments.add(new FragmentItemModel(AdditionalResourcesFragment.newInstance(),
                getResources().getString(R.string.additional_resources_title), FontAwesomeIcons.fa_ellipsis_h));
        return fragments;
    }

    public Fragment getCourseDatesFragment() {
        final StringBuilder courseInfoUrl = new StringBuilder(64);
        courseInfoUrl.append(environment.getConfig().getApiHostURL())
                .append("/courses/")
                .append(courseData.getCourse().getId())
                .append("/info");
        String javascript;
        try {
            javascript = FileUtil.loadTextFileFromAssets(getContext(), "js/filterHtml.js");
        } catch (IOException e) {
            logger.error(e);
            javascript = null;
        }
        if (!TextUtils.isEmpty(javascript)) {
            final CharSequence functionCall = ResourceUtil.getFormattedString(
                    "filterHtmlByClass('date-summary-container', '{not_found_message}');",
                    "not_found_message", getString(R.string.no_course_dates_to_display)
            );
            // Append function call in javascript
            javascript += functionCall;
        }
        return AuthenticatedWebViewFragment.newInstance(courseInfoUrl.toString(), javascript);
    }

    public static class TestFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            TextView tv = new TextView(getContext());
            tv.setText("Content coming soon!");
            tv.setGravity(Gravity.CENTER);
            return tv;
        }
    }
}
