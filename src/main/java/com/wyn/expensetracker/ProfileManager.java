package com.wyn.expensetracker;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class ProfileManager {
    private static final String PROFILES_DIR = "profiles";
    private static final String ACTIVE_PROFILE_FILE = "active_profile.txt";
    static final String DEFAULT_PROFILE = "Default";

    private final String rootDir;

    public ProfileManager() {
        this(System.getProperty("user.home") + File.separator + ".expenseTracker");
    }

    ProfileManager(String rootDir) {
        this.rootDir = rootDir;
    }

    public String getRootDir() {
        return rootDir;
    }

    public String getProfileDir(String profileName) {
        return rootDir + File.separator + PROFILES_DIR + File.separator + profileName;
    }

    public List<String> listProfiles() {
        File profilesDir = new File(rootDir + File.separator + PROFILES_DIR);
        if (!profilesDir.exists()) return new ArrayList<>(List.of(DEFAULT_PROFILE));
        String[] dirs = profilesDir.list((dir, name) -> new File(dir, name).isDirectory());
        if (dirs == null || dirs.length == 0) return new ArrayList<>(List.of(DEFAULT_PROFILE));
        List<String> profiles = new ArrayList<>(Arrays.asList(dirs));
        Collections.sort(profiles);
        return profiles;
    }

    public boolean createProfile(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String sanitized = name.trim();
        if (sanitized.contains(File.separator) || sanitized.contains("/") || sanitized.contains("\\")) return false;
        File dir = new File(getProfileDir(sanitized));
        if (dir.exists()) return false;
        return dir.mkdirs();
    }

    public boolean deleteProfile(String name) {
        List<String> profiles = listProfiles();
        if (profiles.size() <= 1) return false;
        File dir = new File(getProfileDir(name));
        if (!dir.exists()) return false;
        // Delete all files in the profile directory (including backups)
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        return dir.delete();
    }

    public boolean renameProfile(String oldName, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        String sanitized = newName.trim();
        if (sanitized.contains(File.separator) || sanitized.contains("/") || sanitized.contains("\\")) return false;
        File oldDir = new File(getProfileDir(oldName));
        File newDir = new File(getProfileDir(sanitized));
        if (!oldDir.exists() || newDir.exists()) return false;
        return oldDir.renameTo(newDir);
    }

    public String getActiveProfile() {
        File file = new File(rootDir + File.separator + ACTIVE_PROFILE_FILE);
        if (!file.exists()) return DEFAULT_PROFILE;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                File profileDir = new File(getProfileDir(line.trim()));
                if (profileDir.exists()) return line.trim();
            }
        } catch (IOException e) {
            System.err.println("Error reading active profile: " + e.getMessage());
        }
        return DEFAULT_PROFILE;
    }

    public void setActiveProfile(String name) throws IOException {
        Path target = Path.of(rootDir + File.separator + ACTIVE_PROFILE_FILE);
        Files.writeString(target, name);
    }

    /**
     * One-time migration: moves existing data files from the root directory
     * into profiles/Default/. Only runs if the profiles directory doesn't exist yet.
     */
    public void migrateToProfiles() throws IOException {
        File profilesDir = new File(rootDir + File.separator + PROFILES_DIR);
        if (profilesDir.exists()) return; // Already migrated

        File defaultDir = new File(getProfileDir(DEFAULT_PROFILE));
        defaultDir.mkdirs();

        // Check if there's existing data to migrate
        String[] dataFiles = {
            "expenses.txt", "categories.txt", "incomes.txt",
            "settings.txt", "budgets.txt", "categorization_rules.txt",
            "import_log.txt", "ui_state.txt", "expenses.xlsx"
        };

        for (String filename : dataFiles) {
            File src = new File(rootDir + File.separator + filename);
            if (src.exists()) {
                Files.move(src.toPath(),
                    Path.of(defaultDir.getPath() + File.separator + filename),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Move backup files
        for (int i = 1; i <= 5; i++) {
            File backup = new File(rootDir + File.separator + "expenses.txt." + i);
            if (backup.exists()) {
                Files.move(backup.toPath(),
                    Path.of(defaultDir.getPath() + File.separator + "expenses.txt." + i),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
