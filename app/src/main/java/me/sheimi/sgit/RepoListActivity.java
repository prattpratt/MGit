package me.sheimi.sgit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.manichord.mgit.dialogs.CloneDialogViewModel;
import com.manichord.mgit.transport.MGitHttpConnectionFactory;
import com.manichord.mgit.transport.SSLProviderInstaller;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import me.sheimi.android.activities.SheimiFragmentActivity;
import me.sheimi.sgit.activities.RepoDetailActivity;
import me.sheimi.sgit.activities.UserSettingsActivity;
import me.sheimi.sgit.activities.explorer.ExploreFileActivity;
import me.sheimi.sgit.activities.explorer.ImportRepositoryActivity;
import me.sheimi.sgit.adapters.RepoListAdapter;
import me.sheimi.sgit.database.RepoDbManager;
import me.sheimi.sgit.database.models.Repo;
import me.sheimi.sgit.databinding.CloneViewBinding;
import me.sheimi.sgit.dialogs.DummyDialogListener;
import me.sheimi.sgit.dialogs.ImportLocalRepoDialog;
import me.sheimi.sgit.dialogs.InitDialog;
import me.sheimi.sgit.preference.PreferenceHelper;
import me.sheimi.sgit.repo.tasks.repo.CloneTask;
import me.sheimi.sgit.ssh.PrivateKeyUtils;
import timber.log.Timber;

public class RepoListActivity extends SheimiFragmentActivity {

    private ListView mRepoList;
    private Context mContext;
    private RepoListAdapter mRepoListAdapter;

    private CloneDialogViewModel viewModel;

    private static final int REQUEST_IMPORT_REPO = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PrivateKeyUtils.migratePrivateKeys();

        initUpdatedSSL();

        setContentView(R.layout.activity_main);
        mRepoList = (ListView) findViewById(R.id.repoList);
        mRepoListAdapter = new RepoListAdapter(this);
        mRepoList.setAdapter(mRepoListAdapter);
        mRepoListAdapter.queryAllRepo();
        mRepoList.setOnItemClickListener(mRepoListAdapter);
        mRepoList.setOnItemLongClickListener(mRepoListAdapter);
        mContext = getApplicationContext();

        Uri uri = this.getIntent().getData();
        if (uri != null) {
            URL mRemoteRepoUrl = null;
            try {
                mRemoteRepoUrl = new URL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
            } catch (MalformedURLException e) {
                Toast.makeText(mContext, R.string.invalid_url, Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }

            if (mRemoteRepoUrl != null) {
                String remoteUrl = mRemoteRepoUrl.toString();
                String repoName = remoteUrl.substring(remoteUrl.lastIndexOf("/") + 1);
                StringBuilder repoUrlBuilder = new StringBuilder(remoteUrl);

                //need git extension to clone some repos
                if (!remoteUrl.toLowerCase().endsWith(getString(R.string.git_extension))) {
                    repoUrlBuilder.append(getString(R.string.git_extension));
                } else { //if has git extension remove it from repository name
                    repoName = repoName.substring(0, repoName.lastIndexOf('.'));
                }
                //Check if there are others repositories with same remote
                List<Repo> repositoriesWithSameRemote = Repo.getRepoList(mContext, RepoDbManager.searchRepo(remoteUrl));

                //if so, just open it
                if (repositoriesWithSameRemote.size() > 0) {
                    Toast.makeText(mContext, R.string.repository_already_present, Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(mContext, RepoDetailActivity.class);
                    intent.putExtra(Repo.TAG, repositoriesWithSameRemote.get(0));
                    startActivity(intent);
                } else if (Repo.getDir(((SGitApplication) getApplicationContext()).getPrefenceHelper(), repoName).exists()) {
                    // Repository with name end already exists, see https://github.com/maks/MGit/issues/289
                    showCloneView(new CloneDialogViewModel(repoUrlBuilder.toString())).show();
                } else {
                    final String cloningStatus = getString(R.string.cloning);
                    Repo mRepo = Repo.createRepo(repoName, repoUrlBuilder.toString(), cloningStatus);
                    Boolean isRecursive = true;
                    CloneTask task = new CloneTask(mRepo, true, cloningStatus, null);
                    task.executeTask();
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        configSearchAction(searchItem);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.action_new:
                showCloneView(new CloneDialogViewModel("")).show();
                return true;
            case R.id.action_import_repo:
                intent = new Intent(this, ImportRepositoryActivity.class);
                startActivityForResult(intent, REQUEST_IMPORT_REPO);
                forwardTransition();
                return true;
            case R.id.action_settings:
                intent = new Intent(this, UserSettingsActivity.class);
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void configSearchAction(MenuItem searchItem) {
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null)
            return;
        SearchListener searchListener = new SearchListener();
        MenuItemCompat.setOnActionExpandListener(searchItem, searchListener);
        searchView.setIconifiedByDefault(true);
        searchView.setOnQueryTextListener(searchListener);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK)
            return;
        switch (requestCode) {
            case REQUEST_IMPORT_REPO:
                final String path = data.getExtras().getString(
                        ExploreFileActivity.RESULT_PATH);
                File file = new File(path);
                File dotGit = new File(file, Repo.DOT_GIT_DIR);
                if (!dotGit.exists()) {
                    showToastMessage(getString(R.string.error_no_repository));
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(
                        this);
                builder.setTitle(R.string.dialog_comfirm_import_repo_title);
                builder.setMessage(R.string.dialog_comfirm_import_repo_msg);
                builder.setNegativeButton(R.string.label_cancel,
                        new DummyDialogListener());
                builder.setPositiveButton(R.string.label_import,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(
                                    DialogInterface dialogInterface, int i) {
                                Bundle args = new Bundle();
                                args.putString(ImportLocalRepoDialog.FROM_PATH, path);
                                ImportLocalRepoDialog rld = new ImportLocalRepoDialog();
                                rld.setArguments(args);
                                rld.show(getSupportFragmentManager(), "import-local-dialog");
                            }
                        });
                builder.show();
                break;
        }
    }

    public class SearchListener implements SearchView.OnQueryTextListener,
            MenuItemCompat.OnActionExpandListener {

        @Override
        public boolean onQueryTextSubmit(String s) {
            return false;
        }

        @Override
        public boolean onQueryTextChange(String s) {
            mRepoListAdapter.searchRepo(s);
            return false;
        }

        @Override
        public boolean onMenuItemActionExpand(MenuItem menuItem) {
            return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem menuItem) {
            mRepoListAdapter.queryAllRepo();
            return true;
        }

    }

    public void finish() {
        rawfinish();
    }

    private void initUpdatedSSL() {
        if (Build.VERSION.SDK_INT < 21) {
            SSLProviderInstaller.install(this);
        }
        MGitHttpConnectionFactory.install();
        Timber.i("Installed custom HTTPS factory");
    }

    private Dialog showCloneView(final CloneDialogViewModel viewModel) {
        this.viewModel = viewModel;
        final CloneViewBinding binding = DataBindingUtil.inflate(LayoutInflater.from(this), R.layout.clone_view, null, false);
        binding.setVariable(BR.viewModel, viewModel);
        binding.setLifecycleOwner(this);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(binding.getRoot());
        builder.setTitle(R.string.title_clone_repo);
        builder.setNegativeButton(R.string.label_cancel, null);
        builder.setNeutralButton(R.string.dialog_clone_neutral_label,
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    InitDialog id = new InitDialog();
                    id.show(getSupportFragmentManager(), "init-dialog");
                }
            });
        builder.setPositiveButton(R.string.label_clone, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {

            @Override
            public void onShow(DialogInterface dialogInterface) {

                Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (validateRemoteUrl() && validateLocalNameAndHint(binding.localPath)) {
                            viewModel.cloneRepo();
                            dialog.dismiss();
                        }
                    }
                });
            }
        });
        return dialog;
    }

    public boolean validateRemoteUrl() {
        if (viewModel.getRemoteUrl().equals("")) {
            showToastMessage(R.string.alert_remoteurl_required);
            viewModel.setRemoteUrlError(getString(R.string.alert_remoteurl_required));
            return false;
        }
        return true;
    }

    public boolean validateLocalNameAndHint(TextView localPathTextView) {
        if (viewModel.getLocalRepoName().isEmpty()) {
            showToastMessage(R.string.alert_localpath_required);
            viewModel.setLocalRepoNameError(getString(R.string.alert_localpath_required));
            return false;
        }
        if (viewModel.getLocalRepoName().contains("/")) {
            showToastMessage(R.string.alert_localpath_format);
            viewModel.setLocalRepoNameError(getString(R.string.alert_localpath_format));
            return false;
        }
        // If user is accepting the default path in the hint, we need to set localPath to
        // the string in the hint, so that the following checks don't fail.
        if (localPathTextView.getHint().toString() != getString(R.string.dialog_clone_local_path_hint)) {
            viewModel.setLocalRepoName(localPathTextView.getHint().toString());
            return false;
        }

        PreferenceHelper prefsHelper = ((SGitApplication) this.getApplicationContext()).getPrefenceHelper();
        File file = Repo.getDir(prefsHelper, viewModel.getLocalRepoName());
        if (file.exists()) {
            showToastMessage(R.string.alert_localpath_repo_exists);
            viewModel.setLocalRepoNameError(getString(R.string.alert_localpath_repo_exists));
            return false;
        }
        return true;
    }
}
