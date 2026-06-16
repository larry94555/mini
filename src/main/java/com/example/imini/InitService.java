package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Backs the {@code /init} command: scan the workspace, render a {@code CLAUDE.md} draft (deterministic,
 * no model call -- so it is reliable with a weak local model), and create the file if it does not yet
 * exist. An existing {@code CLAUDE.md} is never overwritten implicitly: the draft and the list of
 * sections it is missing are returned for review (explicit overwrite goes through {@code POST /init}).
 *
 * The classification/rendering logic is pure ({@link RepoScan}, {@link InitDraft}); only the filesystem
 * walk and the create-if-absent write live here.
 */
@Component
public class InitService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InitService.class);

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "node_modules", ".imini", "skill-cache", ".gradle", "build", "dist",
            ".idea", ".mvn", "out");
    private static final String TARGET = "CLAUDE.md";

    @Value("${init.scan-max-files:5000}") private int scanMaxFiles;

    private final Path root = Path.of("").toAbsolutePath().normalize();

    public boolean isInitCommand(String msg) {
        return msg != null && msg.trim().equals("/init");
    }

    /** Chat behavior: create CLAUDE.md if absent (and report it); otherwise show the draft + gaps. */
    public String runInit() {
        RepoScan.Facts facts = scan();
        String draft = InitDraft.render(root.getFileName() == null ? "Project" : root.getFileName().toString(), facts);
        Path target = root.resolve(TARGET);
        if (!Files.exists(target)) {
            try {
                Files.writeString(target, draft);
                return "Created " + TARGET + " from a repository scan ("
                        + facts.buildSystem() + ", " + facts.fileCount() + " files). It is now loaded as "
                        + "project memory -- run /memory to confirm. Edit the Conventions/Notes sections.\n\n"
                        + draft;
            } catch (Exception e) {
                return "Could not write " + TARGET + ": " + e.getMessage() + "\n\nProposed draft:\n\n" + draft;
            }
        }
        // CLAUDE.md exists: improve it in place WITHOUT replacing user content -- append only the
        // scaffold sections it is missing (append-only is safe; existing content is never touched).
        String existing;
        try {
            existing = Files.readString(target);
        } catch (Exception e) {
            return "Could not read " + TARGET + ": " + e.getMessage();
        }
        List<String> missing = InitDraft.missingSections(existing, draft);
        if (missing.isEmpty()) {
            return TARGET + " already exists and has all the scaffolded sections; leaving it unchanged. "
                    + "Run /memory to see how it is loaded.";
        }
        String merged = InitDraft.augment(existing, draft);
        try {
            Files.writeString(target, merged);
            return "Improved " + TARGET + " in place: appended " + missing.size() + " missing section(s): "
                    + String.join(", ", missing) + ". Existing content was preserved. Run /memory to confirm.";
        } catch (Exception e) {
            return "Could not write " + TARGET + ": " + e.getMessage()
                    + "\n\nMissing sections you could add: " + String.join(", ", missing);
        }
    }

    /** Back-compat overload (no augment). */
    public Map<String, Object> initInfo(boolean write, boolean overwrite) {
        return initInfo(write, overwrite, false);
    }

    /** Endpoint behavior: optionally write (create if absent; replace with overwrite; merge with augment). */
    public Map<String, Object> initInfo(boolean write, boolean overwrite, boolean augment) {
        RepoScan.Facts facts = scan();
        String draft = InitDraft.render(root.getFileName() == null ? "Project" : root.getFileName().toString(), facts);
        Path target = root.resolve(TARGET);
        boolean exists = Files.exists(target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", TARGET);
        out.put("exists", exists);
        out.put("buildSystem", facts.buildSystem());
        out.put("languages", facts.languages());
        out.put("fileCount", facts.fileCount());
        out.put("missingSections", exists ? missingSections(target, draft) : List.of());
        boolean wrote = false;
        String message;
        if (write && exists && augment && !overwrite) {
            // improve in place: append only missing sections, preserving user content
            try {
                String existing = Files.readString(target);
                List<String> missing = InitDraft.missingSections(existing, draft);
                if (missing.isEmpty()) {
                    message = TARGET + " already has all scaffolded sections; left unchanged";
                } else {
                    Files.writeString(target, InitDraft.augment(existing, draft));
                    wrote = true;
                    message = "augmented " + TARGET + " (+" + missing.size() + " section(s): "
                            + String.join(", ", missing) + ")";
                }
            } catch (Exception e) {
                message = "augment failed: " + e.getMessage();
            }
        } else if (write && (!exists || overwrite)) {
            try {
                Files.writeString(target, draft);
                wrote = true;
                message = exists ? "overwrote " + TARGET : "created " + TARGET;
            } catch (Exception e) {
                message = "write failed: " + e.getMessage();
            }
        } else if (write) {
            message = TARGET + " exists; pass augment=true to add missing sections, or overwrite=true to replace it";
        } else {
            message = exists ? TARGET + " exists (preview only)" : "preview only (not written)";
        }
        out.put("wrote", wrote);
        out.put("message", message);
        out.put("draft", draft);
        return out;
    }

    private List<String> missingSections(Path target, String draft) {
        try {
            return InitDraft.missingSections(Files.readString(target), draft);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Walk the workspace (bounded, skipping build/vcs dirs) and gather facts. */
    private RepoScan.Facts scan() {
        Map<String, Integer> extCounts = new TreeMap<>();
        List<String> rootFiles = new ArrayList<>();
        List<String> topDirs = new ArrayList<>();
        int[] count = {0};

        // top-level entries (root files + directories) for build detection + layout
        try (Stream<Path> top = Files.list(root)) {
            top.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (!SKIP_DIRS.contains(name) && !name.startsWith(".")) topDirs.add(name + "/");
                } else {
                    rootFiles.add(name);
                }
            });
        } catch (Exception e) {
            log.warn("[init] could not list root: " + e.getMessage());
        }
        topDirs.sort(String::compareTo);

        // bounded recursive walk for extension counts
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    for (Path part : root.relativize(p)) if (SKIP_DIRS.contains(part.toString())) return false;
                    return true;
                })
                .limit(scanMaxFiles)
                .forEach(p -> {
                    count[0]++;
                    String e = RepoScan.ext(p.getFileName().toString());
                    if (RepoScan.isCodeExt(e)) extCounts.merge(e, 1, Integer::sum);
                });
        } catch (Exception e) {
            log.warn("[init] walk failed: " + e.getMessage());
        }

        String buildSystem = RepoScan.detectBuildSystem(rootFiles);
        List<String> keyDirs = new ArrayList<>(topDirs);
        for (String nested : List.of("src/main/java", "src/test/java")) {
            if (Files.isDirectory(root.resolve(nested))) keyDirs.add(nested + "/");
        }
        return new RepoScan.Facts(buildSystem, RepoScan.languages(extCounts),
                RepoScan.buildFiles(rootFiles), keyDirs,
                RepoScan.buildCmd(buildSystem), RepoScan.testCmd(buildSystem), count[0]);
    }
}
