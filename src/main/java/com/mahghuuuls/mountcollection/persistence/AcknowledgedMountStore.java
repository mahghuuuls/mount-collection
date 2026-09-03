package com.mahghuuuls.mountcollection.persistence;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/** Writes and reads back the one authoritative WorldSavedData file. */
final class AcknowledgedMountStore {

    enum FailureStage {
        DEVELOPMENT_FAULT,
        DIRECTORY_PREPARATION,
        TEMPORARY_WRITE,
        ATOMIC_REPLACEMENT,
        STORE_WRITE,
        READ_BACK,
        CONTENT_MISMATCH
    }

    interface StoreIo {
        void write(NBTTagCompound root) throws IOException;
        NBTTagCompound read() throws IOException;
    }

    private final StoreIo io;
    private final MountSavedData savedData;
    private final BooleanSupplier acknowledgementFault;
    private final BiConsumer<FailureStage, String> failureReporter;

    AcknowledgedMountStore(File target, MountSavedData savedData) {
        this(target, savedData, () -> false, (stage, cause) -> { });
    }

    AcknowledgedMountStore(
            File target,
            MountSavedData savedData,
            BooleanSupplier acknowledgementFault) {
        this(target, savedData, acknowledgementFault, (stage, cause) -> { });
    }

    AcknowledgedMountStore(
            File target,
            MountSavedData savedData,
            BooleanSupplier acknowledgementFault,
            BiConsumer<FailureStage, String> failureReporter) {
        this(savedData, new FileStoreIo(target), acknowledgementFault, failureReporter);
    }

    AcknowledgedMountStore(MountSavedData savedData, StoreIo io) {
        this(savedData, io, () -> false, (stage, cause) -> { });
    }

    AcknowledgedMountStore(
            MountSavedData savedData,
            StoreIo io,
            BooleanSupplier acknowledgementFault) {
        this(savedData, io, acknowledgementFault, (stage, cause) -> { });
    }

    AcknowledgedMountStore(
            MountSavedData savedData,
            StoreIo io,
            BooleanSupplier acknowledgementFault,
            BiConsumer<FailureStage, String> failureReporter) {
        this.savedData = savedData;
        this.io = io;
        this.acknowledgementFault = Objects.requireNonNull(
                acknowledgementFault, "acknowledgementFault");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    boolean commit(MountRepository.RepositorySnapshot expectedSnapshot) {
        if (acknowledgementFault.getAsBoolean()) {
            savedData.setDirty(true);
            reportFailure(FailureStage.DEVELOPMENT_FAULT, "injected");
            return false;
        }
        NBTTagCompound expectedData = MountStoreCodec.encode(
                expectedSnapshot, new NBTTagCompound());
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("data", expectedData);
        try {
            io.write(root);
        } catch (IOException | RuntimeException exception) {
            savedData.setDirty(true);
            reportFailure(stageOf(exception, FailureStage.STORE_WRITE), causeType(exception));
            return false;
        }
        NBTTagCompound persisted;
        try {
            persisted = io.read();
        } catch (IOException | RuntimeException exception) {
            savedData.setDirty(true);
            reportFailure(stageOf(exception, FailureStage.READ_BACK), causeType(exception));
            return false;
        }
        boolean acknowledged = persisted.hasKey("data", 10)
                && expectedData.equals(persisted.getCompoundTag("data"));
        savedData.setDirty(!acknowledged);
        if (!acknowledged) {
            reportFailure(FailureStage.CONTENT_MISMATCH, "read_back_not_equal");
        }
        return acknowledged;
    }

    private void reportFailure(FailureStage stage, String cause) {
        try {
            failureReporter.accept(stage, cause);
        } catch (RuntimeException ignored) {
            // Diagnostics must never change persistence behavior.
        }
    }

    private static FailureStage stageOf(Throwable failure, FailureStage fallback) {
        return failure instanceof StagedStoreException
                ? ((StagedStoreException) failure).stage
                : fallback;
    }

    private static String causeType(Throwable failure) {
        Throwable cause = failure instanceof StagedStoreException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String simpleName = cause.getClass().getSimpleName();
        return simpleName.isEmpty() ? "unknown" : simpleName;
    }

    private static final class FileStoreIo implements StoreIo {
        private final File target;

        private FileStoreIo(File target) {
            this.target = target;
        }

        @Override
        public void write(NBTTagCompound root) throws IOException {
            File parent;
            try {
                parent = target.getAbsoluteFile().getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new IOException("mount store directory is unavailable");
                }
            } catch (IOException | RuntimeException exception) {
                throw new StagedStoreException(FailureStage.DIRECTORY_PREPARATION, exception);
            }
            File temporary = new File(parent, target.getName() + ".mountcollection.tmp");
            try {
                try {
                    writeAndSync(temporary, root);
                } catch (IOException | RuntimeException exception) {
                    throw new StagedStoreException(FailureStage.TEMPORARY_WRITE, exception);
                }
                try {
                    replace(temporary, target);
                } catch (IOException | RuntimeException exception) {
                    throw new StagedStoreException(FailureStage.ATOMIC_REPLACEMENT, exception);
                }
            } finally {
                if (temporary.exists()) {
                    temporary.delete();
                }
            }
        }

        @Override
        public NBTTagCompound read() throws IOException {
            try {
                try (FileInputStream input = new FileInputStream(target)) {
                    return CompressedStreamTools.readCompressed(input);
                }
            } catch (IOException | RuntimeException exception) {
                throw new StagedStoreException(FailureStage.READ_BACK, exception);
            }
        }

        private static void writeAndSync(File target, NBTTagCompound root) throws IOException {
            try (FileOutputStream output = new FileOutputStream(target)) {
                CompressedStreamTools.writeCompressed(root, new CloseShieldOutputStream(output));
                output.getFD().sync();
            }
        }

        private static void replace(File source, File target) throws IOException {
            try {
                Files.move(
                        source.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        source.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Lets Minecraft finish compression without closing the descriptor before it is synced. */
    private static final class CloseShieldOutputStream extends FilterOutputStream {
        private CloseShieldOutputStream(FileOutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final class StagedStoreException extends IOException {
        private final FailureStage stage;

        private StagedStoreException(FailureStage stage, Throwable cause) {
            super(cause);
            this.stage = stage;
        }
    }
}
