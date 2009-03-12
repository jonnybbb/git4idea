package git4idea.changes;
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
 * Copyright 2008 MQSoftware
 * Authors: Mark Scott
 */

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.RuntimeInterruptedException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.config.GitVcsSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Monitor un-cached filesystem changes in the Git repository
 */
public class ChangeMonitor extends Thread {
    public final static boolean DEBUG = false;
    private static Map<Project, ChangeMonitor> instances = new HashMap<Project, ChangeMonitor>();
    private static int DEF_INTERVAL_SECS = 60;
    private long interval = DEF_INTERVAL_SECS * 1000L;
    private GitVcsSettings settings;
    private Project project;
    private Map<VirtualFile, Set<String>> uncachedFiles = new HashMap<VirtualFile, Set<String>>();
    private Map<VirtualFile, Set<String>> otherFiles = new HashMap<VirtualFile, Set<String>>();
    //private Map<VirtualFile, Set<String>> ignoredFiles = new HashMap<VirtualFile, Set<String>>();
    private boolean running = false;

    public static synchronized ChangeMonitor getInstance(Project proj) {
        ChangeMonitor monitor = instances.get(proj);
        if (monitor == null) {
            monitor = new ChangeMonitor(proj);
            instances.put(proj, monitor);
        }
        return monitor;
    }

    public static synchronized void removeInstance(Project proj) {
        instances.remove(proj);
    }

    /**
     * Create a Git change monitor thread.
     *
     * @param project the VCS project to monitor
     */
    private ChangeMonitor(Project project) {
        super("ChangeMonitor");
        setDaemon(true);
        this.project = project;
    }

    /**
     * Halt the change monitor
     */
    public void stopRunning() {
        running = false;
        interrupt();
    }

    /**
     * Set the Git VCS settings for this change monitor
     *
     * @param gsettings The settings to use
     */
    public void setGitVcsSettings(GitVcsSettings gsettings) {
        settings = gsettings;
    }

    public void start() {
        if(running) return;
        
        if (project == null || settings == null)
            throw new IllegalStateException("Project & VCS settings not set!");
        if (!running) {
            running = true;
            super.start();
        }
    }

    /**
     * Returns the list of Git uncached files for the specified VCS root.
     *
     * @param root the vcs root to lookup
     * @return a list of filenames, or null if none
     */
    public Set<String> getUncachedFiles(VirtualFile root) {
        return uncachedFiles.get(root);
    }

    /**
     * Returns the list of Git unversioned/other files for the specified VCS root.
     *
     * @param root the vcs root to lookup
     * @return a list of filenames, or null if none
     */
    public Set<String> getOtherFiles(VirtualFile root) {
        return otherFiles.get(root);
    }

//    /**
//     * Returns the list of Git ignored files (specified in .gitignore) for the specified VCS root.
//     *
//     * @param root the vcs root to lookup
//     * @return a list of filenames, or null if none
//     */
//    public Set<String> getIgnoredFiles(VirtualFile root) {
//        return ignoredFiles.get(root);
//    }

    @SuppressWarnings({"EmptyCatchBlock"})
    public void run() {
        while (running) {
            try {
                check();
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
            } catch (RuntimeInterruptedException rie) {
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Check to see what files have changed under the monitored content roots.
     */
    @SuppressWarnings({"EmptyCatchBlock"})
    private void check() throws InterruptedException {
        try {
            if (DEBUG) {
                GitVcs.getInstance(project).showMessages("DEBUG: ChangeMonitor.check() start");
            }
            final VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentRoots();
            for (VirtualFile root : roots) {
                if (this.isInterrupted()) throw new InterruptedException("Check interrupted!");
                if (root == null) continue;
                if (DEBUG) {
                    GitVcs.getInstance(project).showMessages("DEBUG: ChangeMonitor root:" + root.getName());
                }
                final GitCommand cmd = new GitCommand(project, settings, root);
                uncachedFiles.put(root, cmd.gitUnCachedFiles());
                otherFiles.put(root, cmd.gitOtherFiles());
                //ignoredFiles.put(root, cmd.gitIgnoredFiles());
            }
            Thread.sleep(5000);

            ApplicationManager.getApplication().invokeLater(
                    new Runnable() {
                        public void run() {
                            for (VirtualFile root : roots) {
                                if (root == null) continue;
                                VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(root);
                            }
                            ChangeListManager.getInstance(project).scheduleUpdate(true);
                        }
                    });
        } catch (VcsException ve) {
            ve.printStackTrace();
        }
        if (DEBUG) {
            GitVcs.getInstance(project).showMessages("DEBUG: ChangeMonitor.check() end");
        }
    }
}