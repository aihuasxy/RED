/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.project.build;

import static com.google.common.collect.Lists.newArrayList;

import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.rf.ide.core.executor.RobotRuntimeEnvironment;
import org.rf.ide.core.project.RobotProjectConfig;
import org.rf.ide.core.project.RobotProjectConfig.ExcludedFolderPath;
import org.robotframework.ide.eclipse.main.plugin.RedPlugin;
import org.robotframework.ide.eclipse.main.plugin.model.RobotSuiteFile;
import org.robotframework.ide.eclipse.main.plugin.project.ASuiteFileDescriber;
import org.robotframework.ide.eclipse.main.plugin.project.LibrariesAutoDiscoverer;
import org.robotframework.ide.eclipse.main.plugin.project.RobotProjectNature;
import org.robotframework.ide.eclipse.main.plugin.project.build.validation.RobotInitFileValidator;
import org.robotframework.ide.eclipse.main.plugin.project.build.validation.RobotProjectConfigFileValidator;
import org.robotframework.ide.eclipse.main.plugin.project.build.validation.RobotResourceFileValidator;
import org.robotframework.ide.eclipse.main.plugin.project.build.validation.RobotSuiteFileValidator;
import org.robotframework.ide.eclipse.main.plugin.project.build.validation.ValidationContext;

import com.google.common.base.Optional;
import com.google.common.collect.Queues;

public class RobotArtifactsValidator {

    private final static ConcurrentHashMap<IResource, Object> VALIDATION_LOCKS = new ConcurrentHashMap<>();

    private final BuildLogger logger;

    private final IProject project;

    public RobotArtifactsValidator(final IProject project, final BuildLogger logger) {
        this.project = project;
        this.logger = logger;
    }

    public static Job revalidate(final RobotSuiteFile suiteModel) {
        if (shouldValidate(suiteModel)) {
            final IFile file = suiteModel.getFile();
            final ValidationContext context = new ValidationContext(file.getProject(), new BuildLogger());

            try {
                final Optional<? extends ModelUnitValidator> validator = ModelUnitValidatorConfigFactory
                        .createValidator(context, file, ProblemsReportingStrategy.reportOnly(), true);
                if (validator.isPresent()) {
                    final WorkspaceJob wsJob = new WorkspaceJob("Revalidating model") {

                        @Override
                        public IStatus runInWorkspace(final IProgressMonitor monitor) throws CoreException {
                            createSynchronizedValidator(file, validator.get()).validate(new NullProgressMonitor());
                            return Status.OK_STATUS;
                        }
                    };
                    wsJob.setSystem(true);
                    wsJob.schedule();
                    return wsJob;
                }
            } catch (final CoreException e) {
                // so we won't revalidate
            }
        }
        return null;
    }

    private static boolean shouldValidate(final RobotSuiteFile suiteModel) {
        final IFile file = suiteModel.getFile();
        if (file == null || !file.exists() || file.getProject() == null || !file.getProject().exists()
                || !RobotProjectNature.hasRobotNature(file.getProject())) {
            return false;
        }
        final RobotRuntimeEnvironment runtimeEnvironment = suiteModel.getProject().getRuntimeEnvironment();
        if (runtimeEnvironment == null || runtimeEnvironment.getVersion() == null) {
            return false;
        }
        return true;
    }
    
    private static synchronized ModelUnitValidator createSynchronizedValidator(final IResource resource,
            final ModelUnitValidator validator) {
        
        return new ModelUnitValidator() {

            @Override
            public void validate(final IProgressMonitor monitor) throws CoreException {
                synchronized (getLock(resource)) {
                    resource.deleteMarkers(RobotProblem.TYPE_ID, true, IResource.DEPTH_ONE);
                    validator.validate(monitor);
                    VALIDATION_LOCKS.remove(resource);
                }
            }

            private Object getLock(final IResource resource) {
                Object newLock = new Object();
                Object oldLock = VALIDATION_LOCKS.putIfAbsent(resource, newLock);
                return oldLock != null ? oldLock : newLock;
            }
        };
    }

    public static Job createValidationJob(final IProject project, final ModelUnitValidatorConfig validatorConfig) {
        return new WorkspaceJob("Validating") {

            @Override
            public IStatus runInWorkspace(final IProgressMonitor monitor) {
                final RobotArtifactsValidator validator = new RobotArtifactsValidator(project, new BuildLogger());
                return validator.runValidation(null, validatorConfig, monitor);
            }
        };
    }

    public Job createValidationJob(final Job dependentJob, final ModelUnitValidatorConfig validatorConfig) {
        return new Job("Validating") {

            @Override
            protected IStatus run(final IProgressMonitor monitor) {
                return runValidation(dependentJob, validatorConfig, monitor);
            }
        };
    }

    private IStatus runValidation(final Job dependentJob, final ModelUnitValidatorConfig validatorConfig,
            final IProgressMonitor monitor) {
        try {
            if (dependentJob != null) {
                dependentJob.join();
                if (!dependentJob.getResult().isOK()) {
                    return Status.CANCEL_STATUS;
                }
            }

            logger.log("VALIDATING: validation of '" + project.getName() + "' project started");
            logger.log("VALIDATING: gathering files to be validated");

            final ValidationContext context = new ValidationContext(project, logger);
            final List<ModelUnitValidator> validators = validatorConfig.createValidators(context);

            validateModelUnits(monitor, Queues.newArrayDeque(validators));

            final Optional<LibrariesAutoDiscoverer> librariesAutoDiscoverer = context.getLibrariesAutoDiscoverer();
            if (librariesAutoDiscoverer.isPresent() && librariesAutoDiscoverer.get().hasSuiteFilesToDiscovering()) {
                librariesAutoDiscoverer.get().start();
            }

            return Status.OK_STATUS;
        } catch (final CoreException | InterruptedException e) {
            RedPlugin.logError("Project validation was corrupted", e);
            return Status.CANCEL_STATUS;
        } finally {
            monitor.done();
        }
    }

    private void validateModelUnits(final IProgressMonitor monitor, final Queue<ModelUnitValidator> validators)
            throws InterruptedException {
        final SubMonitor subMonitor = SubMonitor.convert(monitor, 100);
        subMonitor.beginTask("Validating files", 100);

        final SubMonitor validationSubMonitor = subMonitor.newChild(100);
        validationSubMonitor.setWorkRemaining(validators.size());

        final int threadPoolSize = Runtime.getRuntime().availableProcessors();
        logger.log("VALIDATING: " + threadPoolSize + " threads will be used");
        final ExecutorService threadPool = Executors.newFixedThreadPool(threadPoolSize);

        int current = 1;
        final int total = validators.size();
        while (!validators.isEmpty()) {
            final ModelUnitValidator validator = validators.poll();
            threadPool.submit(createValidationRunnable(monitor, validationSubMonitor, current, total, validator));
            current++;
        }
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.HOURS);
    }

    private Runnable createValidationRunnable(final IProgressMonitor monitor, final SubMonitor validationSubMonitor,
            final int id, final int total, final ModelUnitValidator validator) {
        return new Runnable() {

            @Override
            public void run() {
                try {
                    if (monitor.isCanceled()) {
                        logger.log("VALIDATING: cancelled (" + id + "/" + total + ")");
                        return;
                    }
                    validator.validate(monitor);
                    logger.log("VALIDATING: done (" + id + "/" + total + ")");
                } catch (final Exception e) {
                    logger.log("VALIDATING: error (" + id + "/" + total + ")");
                    logger.logError("VALIDATING: error\n" + e.getMessage(), e);
                } finally {
                    validationSubMonitor.worked(1);
                }
            }
        };
    }

    public interface ModelUnitValidator {

        void validate(IProgressMonitor monitor) throws CoreException;
    }

    public interface ModelUnitValidatorConfig {

        List<ModelUnitValidator> createValidators(final ValidationContext context) throws CoreException;
    }

    public static class ModelUnitValidatorConfigFactory {

        public static ModelUnitValidatorConfig create(final IProject project) {
            return createForWholeProject(project, ProblemsReportingStrategy.reportOnly());
        }

        public static ModelUnitValidatorConfig create(final IProject project, final IResourceDelta delta,
                final int kind, final ProblemsReportingStrategy reporter) {
            if (delta == null || kind == IncrementalProjectBuilder.FULL_BUILD) {
                return createForWholeProject(project, reporter);
            } else {
                return createForChangedFiles(delta, reporter);
            }
        }

        public static ModelUnitValidatorConfig create(final Collection<RobotSuiteFile> suiteModels) {
            return new ModelUnitValidatorConfig() {

                @Override
                public List<ModelUnitValidator> createValidators(final ValidationContext context) throws CoreException {
                    final List<ModelUnitValidator> validators = newArrayList();
                    for (final RobotSuiteFile suiteModel : suiteModels) {
                        if (RobotArtifactsValidator.shouldValidate(suiteModel)) {
                            suiteModel.getFile().accept(new IResourceVisitor() {

                                @Override
                                public boolean visit(final IResource resource) throws CoreException {
                                    final Optional<? extends ModelUnitValidator> validator = createValidator(context,
                                            resource, ProblemsReportingStrategy.reportOnly(), false);
                                    if (validator.isPresent()) {
                                        validators.add(createSynchronizedValidator(resource, validator.get()));
                                    }
                                    return true;
                                }
                            });
                        }
                    }
                    return validators;
                }
            };
        }

        private static ModelUnitValidatorConfig createForWholeProject(final IProject project,
                final ProblemsReportingStrategy reporter) {
            return new ModelUnitValidatorConfig() {

                @Override
                public List<ModelUnitValidator> createValidators(final ValidationContext context) throws CoreException {
                    final List<ModelUnitValidator> validators = newArrayList();
                    project.accept(new IResourceVisitor() {

                        @Override
                        public boolean visit(final IResource resource) throws CoreException {
                            final Optional<? extends ModelUnitValidator> validator = createValidator(context, resource,
                                    reporter, false);
                            if (validator.isPresent()) {
                                validators.add(validator.get());
                            }
                            return true;
                        }
                    });
                    project.deleteMarkers(RobotProblem.TYPE_ID, true, IResource.DEPTH_INFINITE);
                    return validators;
                }
            };
        }

        private static ModelUnitValidatorConfig createForChangedFiles(final IResourceDelta delta,
                final ProblemsReportingStrategy reporter) {
            return new ModelUnitValidatorConfig() {

                @Override
                public List<ModelUnitValidator> createValidators(final ValidationContext context) throws CoreException {
                    final List<ModelUnitValidator> validators = newArrayList();
                    delta.accept(new IResourceDeltaVisitor() {

                        @Override
                        public boolean visit(final IResourceDelta delta) throws CoreException {
                            if (delta.getKind() != IResourceDelta.REMOVED
                                    && (delta.getFlags() & IResourceDelta.CONTENT) != 0) {
                                context.setIsValidatingChangedFiles(true);

                                final IResource resource = delta.getResource();
                                final Optional<? extends ModelUnitValidator> validator = createValidator(context,
                                        resource, reporter, false);
                                if (validator.isPresent()) {
                                    validators.add(createSynchronizedValidator(resource, validator.get()));
                                }
                            }
                            return true;
                        }
                    });
                    return validators;
                }
            };
        }

        private static Optional<? extends ModelUnitValidator> createValidator(final ValidationContext context,
                final IResource resource, final ProblemsReportingStrategy reporter, final boolean isRevalidating)
                throws CoreException {
            return shouldValidate(context.getProjectConfiguration(), resource, isRevalidating)
                    ? createProperValidator(context, (IFile) resource, reporter)
                    : Optional.<ModelUnitValidator> absent();
        }

        private static boolean shouldValidate(final RobotProjectConfig robotProjectConfig, final IResource resource,
                final boolean isRevalidating) {
            if (robotProjectConfig != null && resource.getType() == IResource.FILE
                    && !isInsideEclipseHiddenDirectory(resource)
                    && hasRequiredFileSize(robotProjectConfig, resource, isRevalidating)) {
                final List<ExcludedFolderPath> excludedPaths = robotProjectConfig.getExcludedPath();
                for (final ExcludedFolderPath excludedPath : excludedPaths) {

                    if (Path.fromPortableString(excludedPath.getPath()).isPrefixOf(resource.getProjectRelativePath())) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        private static boolean isInsideEclipseHiddenDirectory(final IResource resource) {
            for (final String segment : resource.getFullPath().segments()) {
                if (!segment.isEmpty() && segment.charAt(0) == '.') {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasRequiredFileSize(final RobotProjectConfig robotProjectConfig,
                final IResource resource, final boolean isRevalidating) {
            if (!isRevalidating && robotProjectConfig.isValidatedFileSizeCheckingEnabled()) {
                final IPath fileLocation = resource.getLocation();
                if (fileLocation != null) {
                    final long fileSizeInKilobytes = fileLocation.toFile().length() / 1024;
                    long maxFileSize = 0L;
                    try {
                        maxFileSize = Long.parseLong(robotProjectConfig.getValidatedFileMaxSize());
                    } catch (final NumberFormatException e) {
                        maxFileSize = Long.parseLong(robotProjectConfig.getValidatedFileDefaultMaxSize());
                    }
                    if (fileSizeInKilobytes > maxFileSize) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static Optional<? extends ModelUnitValidator> createProperValidator(final ValidationContext context,
                final IFile file, final ProblemsReportingStrategy reporter) {

            if (ASuiteFileDescriber.isSuiteFile(file)) {
                return Optional.of(new RobotSuiteFileValidator(context, file, reporter));
            } else if (ASuiteFileDescriber.isResourceFile(file)) {
                return Optional.of(new RobotResourceFileValidator(context, file, reporter));
            } else if (ASuiteFileDescriber.isInitializationFile(file)) {
                return Optional.of(new RobotInitFileValidator(context, file, reporter));
            } else if (file.getName().equals(RobotProjectConfig.FILENAME) && file.getParent() == file.getProject()) {
                return Optional.of(new RobotProjectConfigFileValidator(context, file, reporter));
            }
            return Optional.absent();
        }
    }
}
