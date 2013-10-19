/*
 * Copyright (c) 2012 Zoran Regvart All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */
package org.jenkinsci.plugins.xvfb;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Run.RunnerAbortedException;
import hudson.model.listeners.RunListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class XvfbBuildWrapper extends BuildWrapper {

    @Extension
    public static class XvfbBuildWrapperDescriptor extends BuildWrapperDescriptor {

        /** Xvfb installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile XvfbInstallation[] installations = new XvfbInstallation[0];

        public XvfbBuildWrapperDescriptor() {
            load();
        }

        public FormValidation doCheckDisplayName(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        public FormValidation doCheckDisplayNameOffset(@QueryParameter final String value) throws IOException {
            return validateOptionalPositiveInteger(value);
        }

        public FormValidation doCheckTimeout(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        @Override
        public String getDisplayName() {
            return Messages.XvfbBuildWrapper_DisplayName();
        }

        public XvfbInstallation[] getInstallations() {
            return installations;
        }

        public XvfbInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(XvfbInstallation.DescriptorImpl.class);
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public BuildWrapper newInstance(final StaplerRequest req, final JSONObject formData) throws hudson.model.Descriptor.FormException {
            return req.bindJSON(XvfbBuildWrapper.class, formData);
        }

        public void setInstallations(final XvfbInstallation... installations) {
            this.installations = installations;
            save();
        }

        private FormValidation validateOptionalNonNegativeInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validateNonNegativeInteger(value);
        }

        private FormValidation validateOptionalPositiveInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validatePositiveInteger(value);
        }
    }

    private static final String          STDERR_FD            = "2";

    @SuppressWarnings("rawtypes")
    @Extension
    public static final RunListener<Run> xvfbShutdownListener = new RunListener<Run>() {
                                                                  @Override
                                                                  public void onCompleted(final Run r, final TaskListener listener) {
                                                                      final XvfbEnvironment xvfbEnvironment = r.getAction(XvfbEnvironment.class);

                                                                      if (xvfbEnvironment != null && xvfbEnvironment.isShutdownWithBuild()) {
                                                                          try {
                                                                              shutdownAndCleanup(xvfbEnvironment, listener);
                                                                          } catch (final IOException e) {
                                                                              throw new RuntimeException(e);
                                                                          } catch (final InterruptedException e) {
                                                                              throw new RuntimeException(e);
                                                                          }
                                                                      }
                                                                  }
                                                              };

    private static final int             MILLIS_IN_SECOND     = 1000;

    /** default screen configuration for Xvfb, used by default, and if user left screen configuration blank */
    private static final String          DEFAULT_SCREEN       = "1024x768x24";

    private static void shutdownAndCleanup(final XvfbEnvironment environment, final TaskListener listener) throws IOException, InterruptedException {
        final FilePath frameBufferDir = environment.getFrameBufferDir();
        final Proc process = environment.getProcess();

        listener.getLogger().println(Messages.XvfbBuildWrapper_Stopping());
        process.kill();
        frameBufferDir.deleteRecursive();
    }

    /** Name of the installation used in a configured job. */
    private final String  installationName;

    /** X11 DISPLAY name, if NULL chosen by random. */
    private final Integer displayName;

    /** Xvfb screen argument, in the form WxHxD (width x height x pixel depth), i.e. 800x600x8. */
    private String        screen            = DEFAULT_SCREEN;

    /** Should the Xvfb output be displayed in job output. */
    private boolean       debug             = false;

    /** Time in milliseconds to wait for Xvfb initialization, by default 0 -- do not wait. */
    private final long    timeout;

    /** Offset for display names, default is 1. Display names are taken from build executor's number, i.e. if the build is performed by executor 4, and offset is 100, display name will be 104. */
    private int           displayNameOffset = 1;

    /** Additional options to be passed to Xvfb */
    private final String  additionalOptions;

    /** Should the Xvfb display be around for post build actions, i.e. should it terminate with the whole build */
    private boolean       shutdownWithBuild = false;

    /** Let Xvfb pick display number */
    private boolean       autoDisplayName   = false;

    @DataBoundConstructor
    public XvfbBuildWrapper(final String installationName, final Integer displayName, final String screen, final Boolean debug, final int timeout, final int displayNameOffset,
            final String additionalOptions, final Boolean shutdownWithBuild, final Boolean autoDisplayName) {
        this.installationName = installationName;
        this.displayName = displayName;

        if ("".equals(screen)) {
            this.screen = DEFAULT_SCREEN;
        }
        else {
            this.screen = screen;
        }

        this.debug = Boolean.TRUE.equals(debug);
        this.timeout = timeout;
        if (displayNameOffset <= 0) {
            this.displayNameOffset = 1;
        }
        else {
            this.displayNameOffset = displayNameOffset;
        }
        this.additionalOptions = additionalOptions;

        if (shutdownWithBuild != null) {
            this.shutdownWithBuild = shutdownWithBuild;
        }

        if (autoDisplayName != null) {
            this.autoDisplayName = autoDisplayName;
        }
    }

    public String getAdditionalOptions() {
        return additionalOptions;
    }

    @Override
    public XvfbBuildWrapperDescriptor getDescriptor() {
        return (XvfbBuildWrapperDescriptor) super.getDescriptor();
    }

    public Integer getDisplayName() {
        return displayName;
    }

    public int getDisplayNameOffset() {
        return displayNameOffset;
    }

    public XvfbInstallation getInstallation(final EnvVars env, final Node node, final BuildListener listener) {
        for (final XvfbInstallation installation : getDescriptor().getInstallations()) {
            if (installationName != null && installationName.equals(installation.getName())) {
                try {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                } catch (final IOException e) {
                    listener.fatalError("IOException while locating installation", e);
                } catch (final InterruptedException e) {
                    listener.fatalError("InterruptedException while locating installation", e);
                }
            }
        }

        return null;
    }

    public String getInstallationName() {
        return installationName;
    }

    public String getScreen() {
        return screen;
    }

    public long getTimeout() {
        return timeout;
    }

    public boolean isAutoDisplayName() {
        return autoDisplayName;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isShutdownWithBuild() {
        return shutdownWithBuild;
    }

    private XvfbEnvironment launchXvfb(@SuppressWarnings("rawtypes") final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        int displayNameUsed;

        if (displayName == null) {
            if (!autoDisplayName) {
                final Executor executor = build.getExecutor();
                // BTR: Incorporating the build number here
                displayNameUsed = (executor.getNumber() * 10000) + build.getNumber();
            }
            else {
                displayNameUsed = -1;
            }
        }
        else {
            displayNameUsed = displayName;
        }

        final Computer currentComputer = Computer.currentComputer();
        final Node currentNode = currentComputer.getNode();
        final FilePath rootPath = currentNode.getRootPath();

        final FilePath frameBufferDir = rootPath.createTempDir(build.getId(), "xvfb");

        final EnvVars environment = currentComputer.getEnvironment();
        final XvfbInstallation installation = getInstallation(environment, currentNode, listener);

        if (installation == null) {
            listener.error(Messages.XvfbBuildWrapper_NoInstallationsConfigured());

            throw new InterruptedException();
        }

        final String path = installation.getHome();

        final ArgumentListBuilder cmd;
        if ("".equals(path)) {
            cmd = new ArgumentListBuilder("Xvfb");
        }
        else {
            cmd = new ArgumentListBuilder(path + "/Xvfb");
        }

        if (autoDisplayName) {
            cmd.add("-displayfd", STDERR_FD);
        }
        else {
            cmd.add(":" + displayNameUsed);
        }

        cmd.add("-screen").add("0").add(screen).add("-fbdir").add(frameBufferDir);

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }

        final ProcStarter procStarter = launcher.launch().cmds(cmd);

        final ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        final OutputStream stdout = debug ? listener.getLogger() : stdoutStream;

        final ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stderr = new AutoDisplayNameFilterStream(debug ? listener.getLogger() : stderrStream);

        listener.getLogger().print(Messages.XvfbBuildWrapper_Starting());
        procStarter.stdout(stdout).stderr(stderr);

        final Proc process = procStarter.start();

        Thread.sleep(timeout * MILLIS_IN_SECOND);

        if (!process.isAlive()) {
            if (!debug) {
                listener.getLogger().write(stdoutStream.toByteArray());
                listener.getLogger().write(stderrStream.toByteArray());
            }

            listener.getLogger().println();

            listener.error(Messages.XvfbBuildWrapper_FailedToStart());

            throw new RunnerAbortedException();
        }

        if (autoDisplayName) {
            displayNameUsed = stderr.getDisplayNumber();
        }

        final XvfbEnvironment xvfbEnvironment = new XvfbEnvironment(frameBufferDir, displayNameUsed, process, shutdownWithBuild);

        return xvfbEnvironment;
    }

    @Override
    public void makeBuildVariables(@SuppressWarnings("rawtypes") final AbstractBuild build, final Map<String, String> variables) {
        final XvfbEnvironment xvfbEnvironment = build.getAction(XvfbEnvironment.class);

        if (xvfbEnvironment != null) {
            final int displayNameUsed = xvfbEnvironment.getDisplayNameUsed();

            variables.put("DISPLAY", ":" + displayNameUsed);
        }
    }

    @Override
    public Environment setUp(@SuppressWarnings("rawtypes") final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        if (!launcher.isUnix()) {
            listener.getLogger().println(Messages.XvfbBuildWrapper_NotUnix());

            // we'll return empty environment
            return new Environment() {
            };
        }

        final XvfbEnvironment xvfbEnvironment = launchXvfb(build, launcher, listener);

        build.addAction(xvfbEnvironment);

        final int displayNameUsed = xvfbEnvironment.getDisplayNameUsed();

        return new Environment() {
            @Override
            public void buildEnvVars(final Map<String, String> env) {
                env.put("DISPLAY", ":" + displayNameUsed);
            }

            @Override
            public boolean tearDown(@SuppressWarnings("rawtypes") final AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
                if (!shutdownWithBuild) {
                    shutdownAndCleanup(xvfbEnvironment, listener);
                }

                return true;
            }
        };
    }
}
