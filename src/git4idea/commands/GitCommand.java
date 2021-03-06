package git4idea.commands;
/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 *
 * Copyright 2007 Decentrix Inc
 * Copyright 2007 Aspiro AS
 * Copyright 2008 MQSoftware
 * Authors: gevession, Erlend Simonsen & Mark Scott
 *
 * This code was originally derived from the MKS & Mercurial IDEA VCS plugins
 */

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.EnvironmentUtil;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.actions.GitBranch;
import git4idea.config.GitVcsSettings;
import git4idea.providers.GitFileAnnotation;
import git4idea.vfs.GitContentRevision;
import git4idea.vfs.GitFileRevision;
import git4idea.vfs.GitRevisionNumber;
import git4idea.vfs.GitVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.FileInputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Worker class for executing Git system commands.
 */
@SuppressWarnings({"ResultOfMethodCallIgnored"})
public class GitCommand {
    public final static boolean DEBUG = false;
    public static final int BUF_SIZE = 16 * 1024;  // 16KB
    public static final int MAX_BUF_ALLOWED = 128 * 1024 * 1024; //128MB (who'll ever need to edit a file that big??? :-)
    public static final String EMPTY_STRING = "";
    /* Git/VCS commands */
    private static final String ADD_CMD = "add";
    private static final String ANNOTATE_CMD = "blame";
    private static final String BRANCH_CMD = "branch";
    public static final String CHECKOUT_CMD = "checkout";
    public static final String CLONE_CMD = "clone";
    private static final String COMMIT_CMD = "commit";
    private static final String CONFIG_CMD = "config";
    private static final String DELETE_CMD = "rm";
    private static final String DIFF_CMD = "diff";
    public static final String FETCH_CMD = "fetch";
    private static final String GC_CMD = "gc";
    private static final String LOG_CMD = "log";
    public static final String MERGE_CMD = "merge";
    public static final String MOVE_CMD = "mv";
    public static final String PULL_CMD = "pull";
    public static final String PUSH_CMD = "push";
    private static final String REBASE_CMD = "rebase";
    private static final String REVERT_CMD = "checkout";
    private static final String SHOW_CMD = "show";
    public static final String TAG_CMD = "tag";
    private static final String VERSION_CMD = "version";
    public static final String STASH_CMD = "stash";
    public static final String MERGETOOL_CMD = "mergetool";
    public static final String STATUS_CMD = "ls-files";
    private static final String DIFF_TREE_CMD = "diff-tree";
    private static final String UPDATE_INDEX_CMD = "update-index";

    private static String fileSep = System.getProperty("os.name").startsWith("Windows") ? "\\" : "/";
    private static String pathSep = System.getProperty("path.separator", ";");
    private final static String line_sep = "\n";

    /* Misc Git constants */
    private static final String HEAD = "HEAD";
    private static final Lock gitWriteLock = new ReentrantLock();

    /* Git command env stuff */
    private Project project;
    private final GitVcsSettings settings;
    private VirtualFile vcsRoot;

    public GitCommand(@NotNull final Project project, @NotNull GitVcsSettings settings, @NotNull VirtualFile vcsRoot) {
        this.vcsRoot = vcsRoot;
        this.project = project;
        this.settings = settings;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // General public methods
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the current Git version.
     *
     * @return The version string
     * @throws VcsException If an error occurs
     */
    public String version() throws VcsException {
        return execute(VERSION_CMD);
    }

    /**
     * Returns a list of all local branches
     *
     * @return A list of all the branches
     * @throws VcsException If an error occurs
     */
    public List<GitBranch> branchList() throws VcsException {
        return branchList(false);
    }

    /**
     * Returns a list of all the current branches.
     *
     * @param remoteOnly True if only remote branches should be included
     * @return A list of all the branches
     * @throws VcsException If an error occurs
     */
    public List<GitBranch> branchList(boolean remoteOnly) throws VcsException {
        ArrayList<String> args = new ArrayList<String>();
        if (remoteOnly)
            args.add("-r");
        String result = execute("branch", args, true);
        List<GitBranch> branches = new ArrayList<GitBranch>();

        BufferedReader in = new BufferedReader(new StringReader(result));
        String line;
        try {
            while ((line = in.readLine()) != null) {
                String branchName = line.trim();

                boolean active = false;
                if (branchName.startsWith("* ")) {
                    branchName = branchName.substring(2);
                    active = true;
                }

                boolean remote = branchName.contains("/");
                GitBranch branch = new GitBranch(
                        project,
                        branchName,
                        active,
                        remote);
                branches.add(branch);
            }
        }
        catch (IOException e) {
            throw new VcsException(e);
        }
        return branches;
    }

    /**
     * Returns the name of the currently active branch
     *
     * @return The branch name
     * @throws VcsException If an error occurs
     */
    public String currentBranch() throws VcsException {
        String output = execute(BRANCH_CMD, true);
        StringTokenizer lines = new StringTokenizer(output, line_sep);
        while (lines.hasMoreTokens()) {
            String line = lines.nextToken();
            if (line != null && line.startsWith("*")) {
                return line.substring(2);
            }
        }

        return "master";
    }

    /**
     * Returns the remote repository URL, that a specified remote branch comes from.
     *
     * @param branch The branch to query
     * @return The remote repository URL
     * @throws VcsException if an error occurs
     */
    public String remoteRepoURL(GitBranch branch) throws VcsException {
        String bname = branch.getName();
        if (!branch.isRemote()) return null;
        String remoteAlias = bname.substring(0, bname.indexOf("/"));

        List<String> args = new ArrayList<String>();

        args.add("--get");
        args.add("remote." + remoteAlias + ".url");

        return execute(CONFIG_CMD, args, true);
    }

    /**
     * Returns a set of all changed Git files cached into the Git index under this VCS root.
     *
     * @return The set of all changed files
     * @throws VcsException If an error occurs
     */
    public Set<GitVirtualFile> gitCachedFiles() throws VcsException {
        Set<GitVirtualFile> files = new HashSet<GitVirtualFile>();
        String output;
        List<String> args = new ArrayList<String>();
        args.add("--cached");
        args.add("--name-status");
        args.add("--diff-filter=ADMRUX");
        args.add("--");
        output = execute(DIFF_CMD, args, true);

        StringTokenizer tokenizer;
        if (output != null && output.length() > 0) {
            if (output.startsWith("null"))
                output = output.substring(4);
            tokenizer = new StringTokenizer(output, "\n");
            while (tokenizer.hasMoreTokens()) {
                final String s = tokenizer.nextToken();
                String[] larr = s.split("\t");
                if (larr.length == 2) {
                    GitVirtualFile file = new GitVirtualFile(project, getBasePath() + "/" + larr[1], convertStatus(larr[0]));
                    files.add(file);
                }
            }
        }

        return files;
    }

    /**
     * Returns a set of all changed Git filenames not yet cached into the Git index under this VCS root.
     *
     * @return The set of all changed files
     * @throws VcsException If an error occurs
     */
    public Set<String> gitUnCachedFiles() throws VcsException {
        Set<String> files = new HashSet<String>();
        String output;
        List<String> args = new ArrayList<String>();
        args.add("--name-status");
        args.add("--diff-filter=MRU");
        args.add("--");
        output = execute(DIFF_CMD, args, true);

        StringTokenizer tokenizer;
        if (output != null && output.length() > 0) {
            if (output.startsWith("null"))
                output = output.substring(4);
            tokenizer = new StringTokenizer(output, "\n");
            while (tokenizer.hasMoreTokens()) {
                final String s = tokenizer.nextToken();
                String[] larr = s.split("\t");
                if (larr.length == 2) {
                    files.add(getBasePath() + "/" + larr[1]);
                }
            }
        }

        return files;
    }

    /**
     * Returns a set of all Git-unversioned filenames under this VCS root.
     *
     * @return The set of all changed files
     * @throws VcsException If an error occurs
     */
    public Set<String> gitOtherFiles() throws VcsException {
        Set<String> files = new HashSet<String>();
        String output;
        List<String> args = new ArrayList<String>();
        args.add("--others");
        args.add("--");
        output = execute(STATUS_CMD, args, true);
        if (output != null && output.length() > 0) {
            StringTokenizer tokenizer = new StringTokenizer(output, line_sep);
            while (tokenizer.hasMoreTokens()) {
                final String s = tokenizer.nextToken();
                files.add(getBasePath() + "/" + s.trim());
            }
        }

        return files;
    }

    /**
     * Returns a set of all Git configured ignored filenames under this VCS root.
     *
     * @return The set of all changed files
     * @throws VcsException If an error occurs
     */
    public Set<String> gitIgnoredFiles() throws VcsException {
        Set<String> files = new HashSet<String>();
        String output;
        List<String> args = new ArrayList<String>();
        args.add("--ignored ");
        args.add("--exclude-standard");
        args.add("--");
        output = execute(STATUS_CMD, args, true);
        if (output != null && output.length() > 0) {
            StringTokenizer tokenizer = new StringTokenizer(output, line_sep);
            while (tokenizer.hasMoreTokens()) {
                final String s = tokenizer.nextToken();
                files.add(getBasePath() + "/" + s.trim());
            }
        }

        return files;
    }

    /**
     * Loads the specified revision of a file from Git.
     *
     * @param path     The path to the file.
     * @param revision The revision to load. If the revision is null, then HEAD will be loaded.
     * @return The contents of the revision as a String.
     */
    public String getContents(@NotNull String path, String revision) {
        StringBuffer revCmd = new StringBuffer();
        if (revision != null) {
            if (revision.length() > 40)       // this is the date & revision-id encoded string
                revCmd.append(revision.substring(revision.indexOf("[") + 1, 40));
            else
                revCmd.append(revision);     // either 40 char revision-id or "HEAD", either way just use it
            revCmd.append(":");
        } else {
            revCmd.append(HEAD + ":");
        }

        String vcsPath = revCmd.append(getRelativeFilePath(path, vcsRoot)).toString();
        try {
            return execute(SHOW_CMD, Collections.singletonList(vcsPath), true);
        } catch (VcsException e) {
            return "";
        }
    }

    /**
     * If a Git commit template has been configured, return it's contents.
     * @return  The commit template or null
     */
    public String getCommitTemplate() {
        try {
            String [] args = { "commit.template" };
            String commitTemplateName = execute(CONFIG_CMD, null, args, true);
            if (commitTemplateName == null || commitTemplateName.trim().length() == 0) return null;

            File commitTemplateFile  = new File(commitTemplateName.trim());
            if(!commitTemplateFile.exists()) return null;

            byte[] contents = new byte[ (int) commitTemplateFile.length()];
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(commitTemplateFile);
                fis.read(contents);
                fis.close();
                return new String(contents);
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    if (fis != null)
                        fis.close();
                } catch (IOException ie) {
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds the revision history for the specifid file.
     *
     * @param filePath The path to the file.
     * @return The list.
     * @throws com.intellij.openapi.vcs.VcsException
     *          If it fails...
     */
    public List<VcsFileRevision> log(FilePath filePath) throws VcsException {
        String[] options = new String[]
                {
                        "-C",
                        "-l5",
                        "--find-copies-harder",
                        "-n50",
                        "--pretty=format:%H@@@%an <%ae>@@@%ct@@@%s",
                        "--"
                };

        String[] args = new String[]
                {
                        getRelativeFilePath(filePath.getPath(), vcsRoot)
                };

        String result = execute(LOG_CMD, options, args);

        List<VcsFileRevision> revisions = new ArrayList<VcsFileRevision>();

        // Pull the result apart...
        BufferedReader in = new BufferedReader(new StringReader(result));
        String line;
        //SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        try {
            while ((line = in.readLine()) != null) {
                if (line.length() == 0) continue;
                String[] values = line.split("@@@");
                Date commitDate = new Date(Long.valueOf(values[2]) * 1000);
                //String revstr = df.format(commitDate) + " [" + values[0] + "]";
                String revstr = values[0];
                GitFileRevision revision = new GitFileRevision(
                        project,
                        filePath,
                        new GitRevisionNumber(revstr, commitDate),// git revision id
                        values[1],                // user realname & email
                        values[3],                // commit description
                        null);                    // TODO: find branch name for the commit & pass it here
                revisions.add(revision);
            }

        }
        catch (IOException e) {
            throw new VcsException(e);
        }
        return revisions;
    }


    public Set<GitVirtualFile> virtualFiles(Set<FilePath> fpaths) throws VcsException {
        Set<GitVirtualFile> files = new HashSet<GitVirtualFile>();
        List<String> args = new ArrayList<String>();
        args.add("--name-status");
        args.add("--");

        for (FilePath fpath : fpaths) {
            args.add(getRelativeFilePath(fpath.getPath(), vcsRoot).replace("\\", "/"));
        }

        String output = execute(DIFF_CMD, args, true);

        StringTokenizer tokenizer;
        if (output != null && output.length() > 0) {
            tokenizer = new StringTokenizer(output, line_sep);
            while (tokenizer.hasMoreTokens()) {
                final String s = tokenizer.nextToken();
                String[] larr = s.split("\t");
                if (larr.length == 2) {
                    GitVirtualFile file = new GitVirtualFile(project, getBasePath() + File.separator + larr[1], convertStatus(larr[0]));
                    files.add(file);
                }
            }
        }

        return files;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Public command/action execution methods
    //////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Add the specified files to the repository
     *
     * @param files The files to add
     * @throws VcsException If an error occurs
     */
    public void add(VirtualFile[] files) throws VcsException {
        gitWriteLock.lock();
        try {
            String[] args = new String[files.length];
            int count = 0;
            for (VirtualFile file : files) {
                if (file instanceof GitVirtualFile) {   // don't try to add already deleted files...
                    GitVirtualFile gvf = (GitVirtualFile) file;
                    if (gvf.getStatus() == GitVirtualFile.Status.DELETED)
                        continue;
                }
                if (file != null)
                    args[count++] = getRelativeFilePath(file, vcsRoot);
            }

            String result = execute(ADD_CMD, (String[]) null, args);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Commit the specified files to the repository
     *
     * @param files   The files to commit
     * @param message The commit message description to use
     * @throws VcsException If an error occurs
     */
    @SuppressWarnings({"EmptyCatchBlock"})
    public void commit(VirtualFile[] files, String message) throws VcsException {
        gitWriteLock.lock();
        try {
            StringBuffer commitMessage = new StringBuffer();
            StringTokenizer tok = new StringTokenizer(message, "\n");
            while (tok.hasMoreTokens()) {
                String line = tok.nextToken();
                if (line == null || line.startsWith("#")) // eat all comment lines
                    continue;
                commitMessage.append(line).append("\n");
            }

            String[] options = null;
            File temp;
            BufferedWriter out = null;
            try {
                temp = File.createTempFile("git-commit-msg", ".txt");
                options = new String[]{ "-F", temp.getAbsolutePath()};
                temp.deleteOnExit();
                out = new BufferedWriter(new FileWriter(temp));
                out.write(commitMessage.toString());
            } catch (IOException e) {
            } finally {
                try {
                if(out != null)
                    out.close();
                }catch(IOException ioe) {}
            }

            String[] args = new String[files.length];
            int count = 0;
            for (VirtualFile file : files) {
                if (file != null)
                    args[count++] = getRelativeFilePath(file, vcsRoot);
            }

            add(files); // add current snapshot to index first..
            String result = execute(COMMIT_CMD, options, args);  // now commit the files
            GitVcs.getInstance(project).showMessages(result);

            VcsDirtyScopeManager mgr = VcsDirtyScopeManager.getInstance(project);
            for (VirtualFile file : files) {
                if (file != null) {
                    mgr.fileDirty(file);
                    file.refresh(true, true);
                }
            }
        } finally {
            gitWriteLock.unlock();
        }
        ChangeListManager.getInstance(project).scheduleUpdate(true);
    }

    /**
     * Delete the specified files from the repostory
     *
     * @param files The files to delete
     * @throws VcsException If an error occurs
     */
    public void delete(VirtualFile[] files) throws VcsException {
        gitWriteLock.lock();
        try {
            String[] args = new String[files.length];
            String[] opts = {"-f"};
            int count = 0;
            for (VirtualFile file : files) {
                if (file != null)
                    args[count++] = getRelativeFilePath(file, vcsRoot);
            }

            String result = execute(DELETE_CMD, opts, args);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Checkout the specified branch & create branch if necessary.
     *
     * @param selectedBranch The branch to checkout
     * @param createBranch   True if the branch should be created
     * @throws VcsException If an error occurs
     */
    public void checkout(String selectedBranch, boolean createBranch) throws VcsException {
        gitWriteLock.lock();
        try {
            ArrayList<String> args = new ArrayList<String>();
            if (createBranch) {
                args.add("--track");
                args.add("-b");
            }
            args.add(selectedBranch);

            String result = execute(CHECKOUT_CMD, args);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Clones the repository to the specified path.
     *
     * @param src    The src repository. May be a URL or a path.
     * @param target The target directory
     * @throws VcsException If an error occurs
     */
    public void cloneRepository(String src, String target) throws VcsException {
        gitWriteLock.lock();
        try {
            String[] args = new String[]{src, target};
            String result = execute(CLONE_CMD, (String) null, args);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Merge the current branch
     *
     * @throws VcsException If an error occurs
     */
    public void merge() throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(MERGE_CMD);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Move/rename a file
     *
     * @param oldFile the old file path
     * @param newFile the new file path
     * @throws VcsException If an error occurs
     */
    public void move(@NotNull VirtualFile oldFile, @NotNull VirtualFile newFile) throws VcsException {
        gitWriteLock.lock();
        try {
            String[] files = new String[]{getRelativeFilePath(oldFile.getPath(), vcsRoot),
                    getRelativeFilePath(newFile.getPath(), vcsRoot)};
            String result = execute(MOVE_CMD, files, false);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Cleanup indexes & garbage collect repository
     *
     * @throws VcsException If an error occurs
     */
    public void gc() throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(GC_CMD);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Merge in the specified branch
     *
     * @param branch Teh branch to merge ito the current branch
     * @throws VcsException If an error occurs
     */
    public void merge(GitBranch branch) throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(MERGE_CMD, branch.getName());
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Rebase the current repository.
     *
     * @throws VcsException If an error occurs
     */
    public void rebase() throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(REBASE_CMD);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Pull from the specified repository
     *
     * @param repoURL The repository to pull from
     * @param merge   True if the changes should be merged into the current branch
     * @throws VcsException If an error occurs
     */
    public void pull(String repoURL, boolean merge) throws VcsException {
        gitWriteLock.lock();
        try {
            String cmd;
            if (merge)
                cmd = PULL_CMD;
            else
                cmd = FETCH_CMD;

            String result = execute(cmd, repoURL);
            GitVcs.getInstance(project).showMessages(result);
            result = execute(cmd, "--tags", repoURL);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Push the current branch
     *
     * @throws VcsException If an error occurs
     */
    public void push() throws VcsException {
        String result = execute(PUSH_CMD);
        GitVcs.getInstance(project).showMessages(result);
        result = execute(PUSH_CMD, "--tags");
        GitVcs.getInstance(project).showMessages(result);
    }

    /**
     * Reverts the list of files we are passed.
     *
     * @param files The array of files to revert.
     * @throws VcsException Id it breaks.
     */
    public void revert(VirtualFile[] files) throws VcsException {
        gitWriteLock.lock();
        try {
            StringBuffer result = new StringBuffer();
            for (VirtualFile file : files) {
                if (file != null) {
                    String[] args = new String[]{getRelativeFilePath(file, vcsRoot)};
                    if (gitStatus(file) == GitVirtualFile.Status.ADDED) {
                        String[] options = new String[]{"--force-remove", "--"};
                        result.append(execute(UPDATE_INDEX_CMD, options, args));
                    } else {
                        String[] options = new String[]{HEAD, "--"};
                        result.append(execute(REVERT_CMD, options, args));
                    }
                }
            }
            GitVcs.getInstance(project).showMessages(result.toString());
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Reverts the list of files we are passed.
     *
     * @param files The list of files to revert.
     * @throws VcsException Id it breaks.
     */
    public void revert(List<VirtualFile> files) throws VcsException {
        revert(files.toArray(new VirtualFile[files.size()]));
    }

    /**
     * Tags the current files with the specified tag.
     *
     * @param tagName The tag to use.
     * @throws VcsException If an error occurs
     */
    public void tag(String tagName) throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(TAG_CMD, tagName);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Stash all changes under the specified stash-name
     *
     * @param stashName The name of the stash
     * @throws VcsException If an error occurs
     */
    public void stash(String stashName) throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(STASH_CMD, stashName);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Un-Stash (restore) all changes under the specified stash-name
     *
     * @param stashName The name of the un-stash
     * @throws VcsException If an error occurs
     */
    public void unstash(String stashName) throws VcsException {
        gitWriteLock.lock();
        try {
            String result = execute(STASH_CMD, "apply", stashName);
            GitVcs.getInstance(project).showMessages(result);
        } finally {
            gitWriteLock.unlock();
        }
    }

    /**
     * Returns the current list of all stash names, null if none.
     *
     * @return stash list
     * @throws VcsException If an error occurs
     */
    public String[] stashList() throws VcsException {
        List<String> lines = new LinkedList<String>();

        StringTokenizer tok = new StringTokenizer(execute(STASH_CMD, new String[]{"list"}, true), "\n");
        while (tok.hasMoreTokens()) {
            lines.add(tok.nextToken());
        }

        if (lines.size() == 0) return null;
        return lines.toArray(new String[lines.size()]);
    }

    /**
     * Return true if the specified file is known to Git, otherwise false.
     *
     * @param file the file to check status of
     * @return true if Git owns the file, else false
     * @throws VcsException If an error occurs
     */
    public boolean status(VirtualFile file) throws VcsException {
        String path = getRelativeFilePath(file, GitUtil.getVcsRoot(project, file));
        String output = execute(STATUS_CMD, path);
        return !(output == null || output.length() == 0) && output.contains(path);
    }

    /**
     * Return true if the specified file is known to Git, otherwise false.
     *
     * @param file the file to check status of
     * @return true if Git owns the file, else false
     * @throws VcsException If an error occurs
     */
    public GitVirtualFile.Status gitStatus(VirtualFile file) throws VcsException {
        String path = getRelativeFilePath(file, GitUtil.getVcsRoot(project, file));
        String[] opts = new String[]{"--cached", "--name-status", "--"};
        String[] args = new String[]{path};
        String output = execute(DIFF_CMD, opts, args, true);
        if (output == null || !output.contains(path)) return null;
        return convertStatus(output.split("\t")[0]);
    }

    /**
     * Exec the git merge tool
     *
     * @param files The files to merge
     * @throws VcsException If an error occurs
     */
    public void mergetool(String[] files) throws VcsException {
        String gitcmd;
        File gitExec = new File(settings.GIT_EXECUTABLE);
        if (gitExec.exists())  // use absolute path if we can
            gitcmd = gitExec.getAbsolutePath();
        else
            gitcmd = "git";

        Process proc;
        try {
            List<String> cmdLine = new LinkedList<String>();
            cmdLine.add(gitcmd);
            cmdLine.add(MERGETOOL_CMD);
            if (files != null && files.length > 0) {
                for (String file : files) {
                    if (file != null)
                        cmdLine.add(file);
                }
            }

            File directory = VfsUtil.virtualToIoFile(vcsRoot);
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            // copy IDEA configured env into process exec env
            Map<String, String> pbenv = pb.environment();
            pbenv.putAll(EnvironmentUtil.getEnviromentProperties());
            if (pbenv.get("GIT_DIR") == null)
                pbenv.put("GIT_DIR", directory.getAbsolutePath() + fileSep + ".git");
            String PATH = pbenv.get("PATH");    // fix up path...
            pbenv.put("PATH", gitExec.getParent() + pathSep + PATH);
            pb.directory(directory);
            pb.redirectErrorStream(true);

            if (DEBUG) {
                String cmdStr = StringUtil.join(cmdLine, " ");
                GitVcs.getInstance(project).showMessages("DEBUG: work-dir: [" + directory.getAbsolutePath() + "]" +
                        " exec: [" + cmdStr + "]");
            }

            proc = pb.start();     // we're not waiting for the process, let IDEA continue
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {  // let it fire up, don't care if we get interrupted
            }
        } catch (Exception e) {
            throw new VcsException(e);
        }

        try {
            if (proc.exitValue() != 0) {
                throw new VcsException("Error executing mergetool!");
            }

        } catch (IllegalThreadStateException ie) {
            // ignore if we get this since it means it just hasn't terminated yet... probably working!
        }
    }

    /**
     * Use gitk to show revision graph for specified file.
     *
     * @param file The file to show
     * @throws VcsException if an error occurs
     */
    public void revisionGraph(VirtualFile file) throws VcsException {
        String wishcmd;
        String gitkcmd;
        File gitExec = new File(settings.GIT_EXECUTABLE);
        if (gitExec.exists()) {  // use absolute path if we can
            gitkcmd = gitExec.getParent() + fileSep + "gitk";
            String wishExe = settings.GIT_EXECUTABLE.endsWith(".exe") ? "wish84.exe" : "wish84";
            wishcmd = gitExec.getParent() + fileSep + wishExe;
            File wc = new File(wishcmd);
            if (!wc.exists()) // sometimes wish isn't where git is...
                wishcmd = "wish";
        } else {    // otherwise, assume user has $PATH setup
            wishcmd = "wish84";
            gitkcmd = "gitk";
        }

        String filename = getRelativeFilePath(file, vcsRoot);

        Process proc;
        try {
            File directory = VfsUtil.virtualToIoFile(vcsRoot);
            ProcessBuilder pb = new ProcessBuilder(wishcmd, gitkcmd, filename);
            // copy IDEA configured env into process exec env
            Map<String, String> pbenv = pb.environment();
            pbenv.putAll(EnvironmentUtil.getEnviromentProperties());
            if (pbenv.get("GIT_DIR") == null)
                pbenv.put("GIT_DIR", directory.getAbsolutePath() + fileSep + ".git");
            String PATH = pbenv.get("PATH");    // fix up path so wish can find git when it needs it...

            pbenv.put("PATH", gitExec.getParent() + pathSep + PATH);
            pb.directory(directory);
            pb.redirectErrorStream(true);

            if (DEBUG) {
                String[] cmdLine = new String[]{wishcmd, gitkcmd, filename};
                String cmdStr = StringUtil.join(cmdLine, " ");
                GitVcs.getInstance(project).showMessages("DEBUG: work-dir: [" + directory.getAbsolutePath() + "]" +
                        " exec: [" + cmdStr + "]");
            }

            proc = pb.start();     // we're not waiting for the process, let IDEA continue
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {  // let it fire up, don't care if we get interrupted
            }
        } catch (Exception e) {
            throw new VcsException(e);
        }

        try {
            if (proc.exitValue() != 0)
                throw new VcsException("Error executing gitk!");
        } catch (IllegalThreadStateException ie) {
            // ignore if we get this since it means it just hasn't terminated yet... probably working!
        }
    }

    /**
     * Builds the annotation for the specified file.
     *
     * @param filePath The path to the file.
     * @return The GitFileAnnotation.
     * @throws com.intellij.openapi.vcs.VcsException
     *          If it fails...
     */
    public GitFileAnnotation annotate(FilePath filePath) throws VcsException {
        String[] options = new String[]{"-c", "-C", "-l", "--"};
        String[] args = new String[]{getRelativeFilePath(filePath.getPath(), vcsRoot)};

        GitFileAnnotation annotation = new GitFileAnnotation(project);
        String cmdOutput = execute(ANNOTATE_CMD, options, args);
        if (cmdOutput == null || cmdOutput.length() == 0) return annotation;

        BufferedReader in = new BufferedReader(new StringReader(cmdOutput));
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
        String Line;
        try {
            while ((Line = in.readLine()) != null) {
                String annValues[] = Line.split("\t", 4);
                if (annValues.length != 4) {
                    throw new VcsException("Framing error: unexpected number of values");
                }

                String revision = annValues[0];
                String user = annValues[1];
                String dateStr = annValues[2];
                String numberedLine = annValues[3];

                if (revision.length() != 40) {
                    throw new VcsException("Framing error: Illegal revision number: " + revision);
                }

                int idx = numberedLine.indexOf(')');
                if (!user.startsWith("(") || idx <= 0) {
                    continue;
                }
                user = user.substring(1).trim(); // Ditch the (
                Long lineNumber = Long.valueOf(numberedLine.substring(0, idx));
                String lineContents = numberedLine.substring(idx + 1);

                Date date = dateFormat.parse(dateStr);
                annotation.appendLineInfo(date, new GitRevisionNumber(revision, date), user, lineContents, lineNumber);
            }

        } catch (IOException e) {
            throw new VcsException("Failed to load annotations", e);
        } catch (ParseException e) {
            throw new VcsException("Failed to load annotations", e);
        }
        return annotation;
    }

    /**
     * Builds collection of changed files for a given commit.
     *
     * @param commitId Long commit id.
     * @return Collection of changed files.
     * @throws VcsException if an error occurs
     */
    public Collection<Change> getChangesForCommit(String commitId) throws VcsException {
        final ArrayList<Change> result = new ArrayList<Change>();

        String[] options = new String[]{"-r", "--root", "--pretty=format:%P"}; // Show parent commit if it present
        String[] args = new String[]{commitId};

        String cmdOutput = execute(DIFF_TREE_CMD, options, args);
        final String[] changes = cmdOutput.split("\n");

        if (changes.length == 0) {
            return result;
        }

        GitRevisionNumber parentCommit = null;
        String parentCommitId = changes[0];
        // First line in the output should be id of parent commit. In case if this line is empty it means that commit is initial and has no any parent commit.

        // If so - then given commit could only add files, no change/move/delete allowed. Later we check that such commit has only ADDED file statuses.
        if (parentCommitId.length() > 0) {
            parentCommit = new GitRevisionNumber(parentCommitId);
        }

        for (int i = 1; i < changes.length; i++) {
            String gitChnage = changes[i];
            if (gitChnage.length() == 0)
                continue;

            // format for gitChange is following
            // :000000 100644 0000000000000000000000000000000000000000 984ca539b1c469fb2bbd6d6e26fe5fcd25ab76f1 A	src/git4idea/GitRefactoringListenerProvider.java
            final String[] tokens = gitChnage.split("[ \t]");
            assert tokens.length > 5;
            final String blogIdBefore = tokens[2];
            final String blobIdAfter = tokens[3];
            final GitVirtualFile.Status status = convertStatus(tokens[4].substring(0, 1));
            final String pathArg1 = vcsRoot.getPath() + "/" + tokens[5];
            final String pathArg2 = tokens.length > 6 ? (vcsRoot.getPath() + "/" + tokens[6]) : null;

            ContentRevision before = null;
            ContentRevision after = null;
            FileStatus fileStatus = null;

            switch (status) {
                case MODIFIED:
                    assert parentCommit != null;
                    GitVirtualFile gitFile = new GitVirtualFile(project, pathArg1);
                    before = new GitContentRevision(gitFile, parentCommit, project);
                    after = new GitContentRevision(gitFile, new GitRevisionNumber(commitId), project);
                    fileStatus = FileStatus.MODIFIED;
                    break;
                case COPY:
                case RENAME:
                    assert parentCommit != null;
                    before = new GitContentRevision(new GitVirtualFile(project, pathArg1), parentCommit, project);
                    after = new GitContentRevision(new GitVirtualFile(project, pathArg2), new GitRevisionNumber(commitId), project);
                    fileStatus = FileStatus.MODIFIED;
                    break;
                case ADDED:
                    after = new GitContentRevision(new GitVirtualFile(project, pathArg1), new GitRevisionNumber(commitId), project);
                    fileStatus = FileStatus.ADDED;
                    break;
                case DELETED:
                    assert parentCommit != null;
                    before = new GitContentRevision(new GitVirtualFile(project, pathArg1), parentCommit, project);
                    fileStatus = FileStatus.DELETED;
                    break;
            }

            result.add(new Change(before, after, fileStatus));
        }

        return result;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////
    // Private worker & helper methods
    ////////////////////////////////////////////////////////////////////////////////////////////

    public String getRelativeFilePath(VirtualFile file, @NotNull final VirtualFile baseDir) {
        if (file == null) return null;
        return getRelativeFilePath(file.getPath(), baseDir);
    }

    public String getRelativeFilePath(String file, @NotNull final VirtualFile baseDir) {
        if (file == null) return null;
        String rfile = file.replace("\\", "/");
        final String basePath = baseDir.getPath();
        if (!rfile.startsWith(basePath)) return rfile;
        else if (rfile.equals(basePath)) return ".";
        return rfile.substring(baseDir.getPath().length() + 1);
    }

    private String execute(@NotNull String cmd, String arg) throws VcsException {
        return execute(cmd, null, arg);
    }

    private String execute(@NotNull String cmd, String oneOption, String[] args) throws VcsException {
        String[] options = new String[1];
        options[0] = oneOption;

        return execute(cmd, options, args);
    }

    private String execute(@NotNull String cmd, String option, String arg) throws VcsException {
        String[] options = null;
        if (option != null) {
            options = new String[1];
            options[0] = option;
        }
        String[] args = null;
        if (arg != null) {
            args = new String[1];
            args[0] = arg;
        }

        return execute(cmd, options, args);
    }

    public String execute(@NotNull String cmd, String[] options, String[] args, boolean silent) throws VcsException {
        List<String> cmdArgs = new LinkedList<String>();
        if (options != null && options.length > 0) {
            for (String c : options) {
                if (c != null) cmdArgs.add(c);
            }
        }
        if (args != null && args.length > 0) {
            for (String c : args) {
                if (c != null) cmdArgs.add(c);
            }
        }

        return execute(cmd, cmdArgs, silent);
    }

    private String execute(@NotNull String cmd, String[] options, String[] args) throws VcsException {
        List<String> cmdLine = new ArrayList<String>();
        if (options != null) {
            for (String opt : options) {
                if (opt != null)
                    cmdLine.add(opt);
            }
        }
        if (args != null) {
            for (String arg : args) {
                if (arg != null)
                    cmdLine.add(arg);
            }
        }
        return execute(cmd, cmdLine);
    }

    private String execute(@NotNull String cmd) throws VcsException {
        return execute(cmd, Collections.<String>emptyList());
    }

    private String execute(@NotNull String cmd, List<String> cmdArgs) throws VcsException {
        return execute(cmd, cmdArgs, false);
    }

    private String execute(@NotNull String cmd, boolean silent) throws VcsException {
        return execute(cmd, (List<String>) null, silent);
    }

    private String execute(@NotNull String cmd, String[] cmdArgs, boolean silent) throws VcsException {
        return execute(cmd, Arrays.asList(cmdArgs), silent);
    }

    public String execute(@NotNull String cmd, List<String> cmdArgs, boolean silent) throws VcsException {
        int bufsize = BUF_SIZE;
        List<String> cmdLine = new ArrayList<String>();
        cmdLine.add(settings.GIT_EXECUTABLE);
        cmdLine.add(cmd);
        if (cmdArgs != null) {

            for (String arg : cmdArgs) {
                if (arg != null)
                    cmdLine.add(arg);
            }
        }

        if (cmd.equals(SHOW_CMD) || cmd.equals(ANNOTATE_CMD)) {
            bufsize = BUF_SIZE * 8; // start with bigger buffer when getting contents of files
        }

        File directory = VfsUtil.virtualToIoFile(vcsRoot);

        String cmdStr = null;
        if (DEBUG) {
            cmdStr = StringUtil.join(cmdLine, " ");
            GitVcs.getInstance(project).showMessages("DEBUG: work-dir: [" + directory.getAbsolutePath() + "]" +
                    " exec: [" + cmdStr + "]");
        }

        if (!silent && !DEBUG) { // dont' print twice in DEBUG mode
            if (cmdStr == null)
                cmdStr = StringUtil.join(cmdLine, " ");
            GitVcs.getInstance(project).showMessages("git" + cmdStr.substring(settings.GIT_EXECUTABLE.length()));
        }

        Process proc = null;
        BufferedInputStream in = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            // copy IDEA configured env into process exec env
            Map<String, String> pbenv = pb.environment();
            pbenv.putAll(EnvironmentUtil.getEnviromentProperties());
            if (pbenv.get("GIT_DIR") == null)
                pbenv.put("GIT_DIR", directory.getAbsolutePath() + fileSep + ".git");
            pb.directory(directory);
            pb.redirectErrorStream(true);
            proc = pb.start();

            // Get the output from the process.
            in = new BufferedInputStream(proc.getInputStream());

            byte[] workBuf = new byte[bufsize];
            byte[] retBuf = new byte[bufsize];
            int rlen = in.read(workBuf);   // length of current read
            int wpos = 0; // total count of all bytes read (also write position in retBuf)
            while (rlen != -1) {
                if ((wpos + rlen) > retBuf.length) {  // handle *big* output....
                    if ((retBuf.length * 2) >= MAX_BUF_ALLOWED)
                        throw new VcsException("Git command output limit exceeded, cannot process!");
                    byte[] newbuf = new byte[retBuf.length * 2];
                    System.arraycopy(retBuf, 0, newbuf, 0, wpos);
                    retBuf = newbuf;
                }
                System.arraycopy(workBuf, 0, retBuf, wpos, rlen);
                wpos += rlen;
                rlen = in.read(workBuf);
            }

            try {
                proc.waitFor();
            } catch (InterruptedException ie) {
                return EMPTY_STRING;
            }

            if (wpos == 0) return EMPTY_STRING;
            String output = new String(retBuf, 0, wpos);

            // empty repo with no commits yet...
            if (cmd.equals(DIFF_CMD) && output.contains("No HEAD commit to compare with"))
                return EMPTY_STRING;

            if (proc.exitValue() != 0)
                throw new VcsException(output);

            return output;
        }
        catch (IOException e) {
            throw new VcsException(e.getMessage());
        } finally {
            try {
                if(in != null) in.close();
            } catch (IOException e) {}
            if(proc != null) proc.destroy();
        }
    }

    /**
     * Returns the base path of the project.
     *
     * @return The base path of the project.
     */
    private String getBasePath() {
        return vcsRoot.getPath();
    }

    /**
     * Helper method to convert String status' from the git output to a GitFile status
     *
     * @param status The status from git as a String.
     * @return The git file status.
     * @throws com.intellij.openapi.vcs.VcsException
     *          something bad had happened
     */
    public GitVirtualFile.Status convertStatus(String status) throws VcsException {
        if (status.equals("M"))
            return GitVirtualFile.Status.MODIFIED;
        else if (status.equals("H"))
            return GitVirtualFile.Status.MODIFIED;
        else if (status.equals("C"))
            return GitVirtualFile.Status.COPY;
        else if (status.equals("R"))
            return GitVirtualFile.Status.RENAME;
        else if (status.equals("A"))
            return GitVirtualFile.Status.ADDED;
        else if (status.equals("D"))
            return GitVirtualFile.Status.DELETED;
        else if (status.equals("U"))
            return GitVirtualFile.Status.UNMERGED;
        else if (status.equals("X"))
            return GitVirtualFile.Status.UNVERSIONED;
        else
            return GitVirtualFile.Status.UNMODIFIED;
    }
}