package com.techconnect.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/skill-assessment")
public class SkillAssessmentController {

    private final JdbcTemplate jdbc;

    public SkillAssessmentController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Question bank ─────────────────────────────────────────────────────────
    private static final Map<String, List<Map<String, Object>>> QUESTIONS = new LinkedHashMap<>();
    static {
        QUESTIONS.put("javascript", List.of(
            q(1, "What does `typeof null` return?",
              List.of("null","undefined","object","string"), 2),
            q(2, "Which method adds an element to the end of an array?",
              List.of("push","pop","shift","unshift"), 0),
            q(3, "What is the output of `0 == false`?",
              List.of("false","true","undefined","TypeError"), 1),
            q(4, "Which keyword declares a block-scoped variable?",
              List.of("var","let","const","both let and const"), 3),
            q(5, "What does `===` check compared to `==`?",
              List.of("Nothing, they are the same","Value and type","Only type","Only value"), 1),
            q(6, "What does `Array.isArray([])` return?",
              List.of("false","true","undefined","TypeError"), 1),
            q(7, "Which is NOT a falsy value in JavaScript?",
              List.of("0","\"\"","\"false\"","null"), 2),
            q(8, "What does the spread operator `...` do?",
              List.of("Deletes array items","Expands iterables into individual elements","Creates a deep clone","Merges two objects into a class"), 1),
            q(9, "What does `Promise.all([])` return when passed an empty array?",
              List.of("null","A pending promise","A resolved promise with []","A rejected promise"), 2),
            q(10, "Which method transforms every element of an array and returns a new array?",
              List.of("forEach","filter","map","reduce"), 2)
        ));
        QUESTIONS.put("python", List.of(
            q(1, "What does `len('hello')` return?",
              List.of("4","5","6","TypeError"), 1),
            q(2, "Which of these creates a dictionary in Python?",
              List.of("[]","()","{}","<>"), 2),
            q(3, "What is the output of `print(type([]))`?",
              List.of("<class 'tuple'>","<class 'list'>","<class 'array'>","<class 'dict'>"), 1),
            q(4, "How do you start a comment in Python?",
              List.of("//","#","/*","--"), 1),
            q(5, "What does `range(3)` produce?",
              List.of("[1,2,3]","[0,1,2,3]","[0,1,2]","(0,1,2,3)"), 2),
            q(6, "Which keyword is used to handle exceptions in Python?",
              List.of("catch","rescue","except","handle"), 2),
            q(7, "What does `list.append(x)` do?",
              List.of("Adds x to the beginning","Removes x","Adds x to the end","Replaces last element with x"), 2),
            q(8, "What is the result of `2 ** 3` in Python?",
              List.of("6","8","9","None"), 1),
            q(9, "Which built-in function returns the largest item?",
              List.of("greatest","max","top","highest"), 1),
            q(10, "How do you open a file for reading in Python?",
              List.of("open('f','w')","open('f','r')","open('f','a')","open('f','x')"), 1)
        ));
        QUESTIONS.put("sql", List.of(
            q(1, "Which SQL clause filters rows after grouping?",
              List.of("WHERE","FILTER","HAVING","LIMIT"), 2),
            q(2, "What does `SELECT DISTINCT` do?",
              List.of("Sorts results","Returns only unique rows","Speeds up queries","Limits to 1 row per table"), 1),
            q(3, "Which join returns all rows from both tables?",
              List.of("INNER JOIN","LEFT JOIN","RIGHT JOIN","FULL OUTER JOIN"), 3),
            q(4, "What does `NULL = NULL` evaluate to in SQL?",
              List.of("TRUE","FALSE","NULL","1"), 2),
            q(5, "Which aggregate function counts non-null values?",
              List.of("SUM","AVG","COUNT","MAX"), 2),
            q(6, "What does `TRUNCATE TABLE` do vs `DELETE`?",
              List.of("Same thing","Truncate removes all rows faster and resets auto-increment","Delete is faster","Truncate keeps constraints, delete doesn't"), 1),
            q(7, "Which keyword prevents duplicate inserts?",
              List.of("SAFE INSERT","INSERT ONCE","INSERT OR IGNORE","UNIQUE INSERT"), 2),
            q(8, "What does an INDEX primarily improve?",
              List.of("INSERT speed","DELETE speed","SELECT query speed","Storage space"), 2),
            q(9, "Which clause limits the number of returned rows?",
              List.of("TOP","ROWNUM","FETCH","LIMIT"), 3),
            q(10, "What does `ON DELETE CASCADE` mean on a foreign key?",
              List.of("Prevents deleting the parent","Deletes child rows when parent is deleted","Nullifies child FK on parent delete","Logs the deletion"), 1)
        ));
        QUESTIONS.put("java", List.of(
            q(1, "Which access modifier makes a member accessible only within its class?",
              List.of("public","protected","private","default"), 2),
            q(2, "What does `final` mean on a variable?",
              List.of("It's static","It cannot be reassigned after initialization","It's thread-safe","It's garbage collected immediately"), 1),
            q(3, "Which collection guarantees key uniqueness?",
              List.of("ArrayList","LinkedList","HashMap","TreeList"), 2),
            q(4, "What is the parent class of all Java classes?",
              List.of("Base","Root","Object","Class"), 2),
            q(5, "What does `@Override` annotation mean?",
              List.of("Creates a new method","Marks a method as deprecated","Indicates the method overrides a superclass method","Makes the method static"), 2),
            q(6, "Which exception is thrown for array out-of-bounds access?",
              List.of("NullPointerException","ArrayIndexOutOfBoundsException","IndexException","BoundsException"), 1),
            q(7, "What is autoboxing?",
              List.of("Converting int[] to ArrayList","Automatic conversion between primitives and their wrapper classes","A JVM optimization","A way to box exceptions"), 1),
            q(8, "Which interface should a class implement to be sortable with `Collections.sort()`?",
              List.of("Sortable","Orderable","Comparable","Comparator"), 2),
            q(9, "What does `instanceof` check?",
              List.of("If a variable is null","Whether an object is an instance of a class or interface","Memory address equality","Method existence"), 1),
            q(10, "Which Java keyword is used to create a subclass?",
              List.of("implements","inherits","extends","subclass"), 2)
        ));
        QUESTIONS.put("html", List.of(
            q(1, "Which HTML tag is used for the largest heading?",
              List.of("<head>","<h6>","<h1>","<title>"), 2),
            q(2, "What attribute makes an input field required?",
              List.of("mandatory","required","must","validate"), 1),
            q(3, "Which element defines a navigation section?",
              List.of("<section>","<nav>","<header>","<menu>"), 1),
            q(4, "What does the `alt` attribute on `<img>` do?",
              List.of("Sets image size","Provides alternative text if image fails to load","Links to another image","Adds a tooltip"), 1),
            q(5, "Which input type creates a checkbox?",
              List.of("check","tick","checkbox","boolean"), 2),
            q(6, "What does `<!DOCTYPE html>` declare?",
              List.of("Encoding","HTML version as HTML5","Page title","DOCTYPE is the page author"), 1),
            q(7, "Which HTML5 element is used for independent, self-contained content?",
              List.of("<section>","<article>","<aside>","<div>"), 1),
            q(8, "How do you create an unordered list in HTML?",
              List.of("<ol>","<list>","<ul>","<dl>"), 2),
            q(9, "Which attribute specifies the URL for a hyperlink?",
              List.of("src","link","href","url"), 2),
            q(10, "What does the `<meta charset='UTF-8'>` tag do?",
              List.of("Sets page language","Specifies the character encoding","Sets the page description","Adds a viewport"), 1)
        ));
        QUESTIONS.put("react", List.of(
            q(1, "What hook manages local state in a functional component?",
              List.of("useEffect","useRef","useState","useContext"), 2),
            q(2, "What does `useEffect` with an empty dependency array `[]` do?",
              List.of("Runs on every render","Runs once after the first render","Never runs","Runs before the first render"), 1),
            q(3, "How do you pass data from parent to child in React?",
              List.of("State","Context","Props","Events"), 2),
            q(4, "What is the virtual DOM?",
              List.of("A browser API","A lightweight in-memory copy of the real DOM","A React database","A server-side rendering technique"), 1),
            q(5, "Which method updates state in a class component?",
              List.of("this.state =","this.changeState","this.setState","this.updateState"), 2),
            q(6, "What does `key` prop help React do in lists?",
              List.of("Style list items","Efficiently update and re-render list items","Pass data to list items","Create unique IDs"), 1),
            q(7, "What is JSX?",
              List.of("A JavaScript library","A syntax extension that looks like HTML inside JS","A CSS-in-JS framework","A state manager"), 1),
            q(8, "What does `React.memo()` do?",
              List.of("Creates a memo note","Prevents re-renders when props have not changed","Memoizes async functions","Caches API responses"), 1),
            q(9, "Which hook lets you access a DOM element directly?",
              List.of("useState","useMemo","useRef","useDOM"), 2),
            q(10, "What is the correct way to conditionally render in JSX?",
              List.of("if/else statement directly in JSX","&&  operator or ternary","switch/case","for loop"), 1)
        ));
        QUESTIONS.put("git", List.of(
            q(1, "Which command stages all changes for commit?",
              List.of("git commit -a","git add .","git stage all","git push"), 1),
            q(2, "What does `git stash` do?",
              List.of("Deletes uncommitted changes","Saves uncommitted changes temporarily","Pushes to remote","Creates a backup branch"), 1),
            q(3, "How do you create and switch to a new branch in one command?",
              List.of("git branch new","git checkout -b new","git switch new","git create new"), 1),
            q(4, "What does `git rebase` do?",
              List.of("Merges two branches with a merge commit","Moves a branch's commits to a new base","Deletes a branch","Resets the HEAD"), 1),
            q(5, "Which command shows the commit history?",
              List.of("git status","git diff","git log","git show"), 2),
            q(6, "What is a 'detached HEAD' state?",
              List.of("A corrupted repository","HEAD points directly to a commit instead of a branch","A merge conflict state","An untracked file state"), 1),
            q(7, "What does `git fetch` do?",
              List.of("Downloads remote changes but does NOT merge them","Downloads and merges remote changes","Pushes local changes","Deletes remote branches locally"), 0),
            q(8, "How do you undo the last commit but keep the changes staged?",
              List.of("git reset --hard HEAD~1","git revert HEAD","git reset --soft HEAD~1","git checkout HEAD~1"), 2),
            q(9, "What does `.gitignore` do?",
              List.of("Ignores files that are already committed","Specifies files Git should not track","Hides files from the OS","Removes files from history"), 1),
            q(10, "Which command merges a branch and creates a merge commit?",
              List.of("git rebase","git cherry-pick","git merge","git pull --rebase"), 2)
        ));
    }

    private static Map<String, Object> q(int id, String question, List<String> options, int answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       id);
        m.put("question", question);
        m.put("options",  options);
        m.put("answer",   answer);
        return m;
    }

    // ── GET /api/skill-assessment/{skill} — return questions without answers ──
    @GetMapping("/{skill}")
    public ResponseEntity<Map<String, Object>> getQuestions(
            @PathVariable String skill, HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String key = skill.toLowerCase().replaceAll("[^a-z]", "");
        List<Map<String, Object>> bank = QUESTIONS.get(key);
        if (bank == null)
            return ResponseEntity.status(404).body(Map.of("error", "No assessment available for: " + skill));

        // Check cooldown (24h between attempts)
        List<Map<String, Object>> badge = jdbc.queryForList(
            "SELECT earned_at FROM skill_badges WHERE user_id=? AND skill_name=?", uid, key);
        if (!badge.isEmpty()) {
            String earnedAt = badge.get(0).get("earned_at").toString();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("alreadyPassed", true);
            resp.put("earnedAt", earnedAt);
            return ResponseEntity.ok(resp);
        }

        // Shuffle and strip answers
        List<Map<String, Object>> shuffled = new ArrayList<>(bank);
        Collections.shuffle(shuffled);
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Map<String, Object> q : shuffled) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("id",       q.get("id"));
            safe.put("question", q.get("question"));
            safe.put("options",  q.get("options"));
            questions.add(safe);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("skill",     key);
        resp.put("total",     questions.size());
        resp.put("questions", questions);
        return ResponseEntity.ok(resp);
    }

    // ── POST /api/skill-assessment/{skill}/submit — score and optionally badge ─
    @PostMapping("/{skill}/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable String skill,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        String key = skill.toLowerCase().replaceAll("[^a-z]", "");
        List<Map<String, Object>> bank = QUESTIONS.get(key);
        if (bank == null)
            return ResponseEntity.status(404).body(Map.of("error", "Unknown skill."));

        // Already has badge?
        Integer hasBadge = jdbc.queryForObject(
            "SELECT COUNT(*) FROM skill_badges WHERE user_id=? AND skill_name=?",
            Integer.class, uid, key);
        if (hasBadge != null && hasBadge > 0)
            return ResponseEntity.ok(Map.of("alreadyPassed", true));

        // Parse submitted answers: { "answers": {1: 2, 2: 0, ...} } by question id
        Object rawAnswers = body.get("answers");
        if (!(rawAnswers instanceof Map<?, ?> submittedMap))
            return ResponseEntity.status(400).body(Map.of("error", "answers map required."));

        int correct = 0;
        int total   = bank.size();
        for (Map<String, Object> q : bank) {
            int qid     = ((Number) q.get("id")).intValue();
            int correct_ans = ((Number) q.get("answer")).intValue();
            Object submitted = submittedMap.get(String.valueOf(qid));
            if (submitted == null) submitted = submittedMap.get(qid);
            if (submitted instanceof Number n && n.intValue() == correct_ans) correct++;
        }

        int  score   = correct;
        boolean passed = score >= 7; // 70% threshold

        if (passed) {
            try {
                jdbc.update("""
                    INSERT INTO skill_badges (user_id, skill_name, score)
                    VALUES (?, ?, ?)
                    ON CONFLICT(user_id, skill_name) DO UPDATE SET score=excluded.score, earned_at=datetime('now')
                    """, uid, key, score);
            } catch (Exception ignored) {}
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("score",  score);
        resp.put("total",  total);
        resp.put("passed", passed);
        resp.put("skill",  key);
        return ResponseEntity.ok(resp);
    }

    // ── GET /api/skill-assessment/my-badges — list my earned badges ───────────
    @GetMapping("/my-badges")
    public ResponseEntity<List<String>> myBadges(HttpSession session) {
        String uid = (String) session.getAttribute("userId");
        if (uid == null) return ResponseEntity.status(401).build();

        List<String> badges = jdbc.queryForList(
            "SELECT skill_name FROM skill_badges WHERE user_id=? ORDER BY earned_at DESC",
            String.class, uid);
        return ResponseEntity.ok(badges);
    }

    // ── GET /api/skill-assessment/badges/{userId} — list badges for any user ──
    @GetMapping("/badges/{userId}")
    public ResponseEntity<List<String>> userBadges(
            @PathVariable String userId, HttpSession session) {
        if (session.getAttribute("userId") == null)
            return ResponseEntity.status(401).build();

        List<String> badges = jdbc.queryForList(
            "SELECT skill_name FROM skill_badges WHERE user_id=? ORDER BY earned_at DESC",
            String.class, userId);
        return ResponseEntity.ok(badges);
    }
}
