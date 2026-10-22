package com.example.testutil;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * API 34 AtomicFile uses File.renameTo for same-directory atomic replacement.
 * Android replaces an existing destination; Windows' host JVM does not.
 * Keep the real AtomicFile write/read/recovery code, adapting only that private
 * filesystem operation for these host tests. This class is never in the APK.
 */
@Implements(value = AtomicFile.class, minSdk = 34, maxSdk = 34)
public class AndroidAtomicFileShadow {
    @Implementation
    protected static void rename(File source, File target) {
        if (target.isDirectory() && !target.delete()) {
            Log.e("AtomicFile", "Could not delete the target directory.");
        }
        try {
            Files.move(source.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            // Android logs a failed rename; its caller must verify the commit.
            // Do not substitute a non-atomic copy or claim a successful write.
            Log.e("AtomicFile", "Atomic replacement failed.");
        }
    }
}
