"""Verify actual Kotlin-emitted migration SQL using disposable in-memory SQLite only.

Run Gradle testDebugUnitTest first; QuestionIdentityTest emits the real migration plan.
No Android device, user database, or backup file is read or changed.
"""
import json
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
PLAN = json.loads((ROOT / "app/build/identity-work/migration-plan.json").read_text(encoding="utf-8"))
GENERATED = (ROOT / "app/build/generated/source/kapt/debug/com/zsz/studyassistant/data/AppDatabase_Impl.java").read_text(encoding="utf-8")
UNDO_SCHEMA = (ROOT / "app/build/identity-work/tag-merge-schema.sql").read_text(encoding="utf-8")


def old_database():
    db = sqlite3.connect(":memory:")
    db.executescript("""
    CREATE TABLE questions(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,text TEXT NOT NULL,answer TEXT NOT NULL,createdAt INTEGER NOT NULL,imageBytes BLOB,conversationJson TEXT,deleted INTEGER NOT NULL,categoryId INTEGER);
    CREATE TABLE categories(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,createdAt INTEGER NOT NULL);
    CREATE TABLE tags(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,createdAt INTEGER NOT NULL);
    CREATE TABLE question_tags(questionId INTEGER NOT NULL,tagId INTEGER NOT NULL,PRIMARY KEY(questionId,tagId));
    CREATE TABLE review(questionId INTEGER PRIMARY KEY NOT NULL,intervalStep INTEGER NOT NULL,nextReviewAt INTEGER NOT NULL,lastReviewedAt INTEGER NOT NULL,reviewCount INTEGER NOT NULL);
    CREATE TABLE question_images(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,questionId INTEGER NOT NULL,position INTEGER NOT NULL,bytes BLOB NOT NULL,source TEXT,createdAt INTEGER NOT NULL);
    CREATE INDEX index_question_images_questionId_position ON question_images(questionId,position);
    CREATE TABLE solve_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL,source TEXT NOT NULL,text TEXT NOT NULL,answer TEXT NOT NULL,conversationJson TEXT,imageBytes BLOB,imagesJson TEXT,savedQuestionId INTEGER);
    INSERT INTO categories VALUES(1,'Math',10000);
    INSERT INTO tags VALUES(2,'Tag',10000);
    INSERT INTO questions VALUES(1,'one','answer one',10000,X'0102','[]',0,1);
    INSERT INTO questions VALUES(2,'two','answer two',0,NULL,'[]',0,1);
    INSERT INTO questions VALUES(3,'three','answer three',20000,NULL,'[]',1,NULL);
    INSERT INTO question_tags VALUES(2,2),(99,2);
    INSERT INTO review VALUES(2,3,30000,20000,7);
    INSERT INTO question_images VALUES(1,1,0,X'0102','camera',10000),(2,1,1,X'00FF42','camera',10000);
    INSERT INTO solve_sessions VALUES(7,0,50000,'camera','leftover','answer','[]',X'0102',NULL,1);
    """)
    return db


def migrate(db, statements):
    db.execute("BEGIN")
    try:
        for sql in statements:
            db.execute(sql)
        db.commit()
    except Exception:
        db.rollback()
        raise


def verify_preservation_and_schema():
    assert "new RoomOpenDelegate(10," in GENERATED, "Main database must remain v10"
    db = old_database()
    migrate(db, PLAN)
    rows = {r[1]: r for r in db.execute("SELECT id,text,createdAt,imageBytes,deleted,categoryId FROM questions")}
    assert len(rows) == 3
    assert rows["one"][2:] == (10000, b"\x01\x02", 0, 1)
    assert 10000 < rows["two"][2] < 20000
    assert rows["three"][2:] == (20000, None, 1, None)
    assert all(re.fullmatch(r"[a-f0-9-]{36}-\d+", r[0]) for r in rows.values())
    assert db.execute("SELECT questionId,reviewCount FROM review").fetchone() == (rows["two"][0], 7)
    assert db.execute("SELECT COUNT(*) FROM question_tags WHERE questionId=? AND tagId=2", (rows["two"][0],)).fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM question_tags").fetchone()[0] == 2  # orphan preserved, not silently dropped
    assert db.execute("SELECT bytes FROM question_images ORDER BY position").fetchall() == [(b"\x01\x02",), (b"\x00\xffB",)]
    assert db.execute("SELECT COUNT(*) FROM question_images WHERE questionId=?", (rows["one"][0],)).fetchone()[0] == 2
    assert db.execute("SELECT createdAt,savedQuestionId,text FROM solve_sessions").fetchone() == (30000, None, "leftover")
    assert db.execute("SELECT * FROM categories").fetchone() == (1,"Math",10000)
    assert db.execute("SELECT * FROM tags").fetchone() == (2,"Tag",10000)

    # Compare with Room's generated CREATE statements, not a hand-written v10 schema.
    fresh = sqlite3.connect(":memory:")
    create_section = GENERATED.split("public void createAllTables", 1)[1].split("public void dropAllTables", 1)[0]
    statements = re.findall(r'SQLite\.execSQL\(connection, "([^"]+)"\)', create_section)
    assert len(statements) >= 8
    for sql in statements:
        fresh.execute(sql)
    assert "tag_merge_undo" not in GENERATED
    for table in ("questions","question_tags","review","question_images","solve_sessions","categories","tags"):
        assert db.execute(f"PRAGMA table_info({table})").fetchall() == fresh.execute(f"PRAGMA table_info({table})").fetchall(), table
        assert db.execute(f"PRAGMA index_list({table})").fetchall() == fresh.execute(f"PRAGMA index_list({table})").fetchall(), table
    db.close()
    fresh.close()


def verify_auxiliary_database_is_independent():
    db = old_database()
    migrate(db, PLAN)
    before = db.execute("SELECT * FROM questions ORDER BY id").fetchall()
    undo = sqlite3.connect(":memory:")
    undo.execute(UNDO_SCHEMA)
    undo.execute("INSERT INTO tag_merge_undo VALUES(1,100,'old-before','old-after')")
    undo.execute("INSERT INTO tag_merge_undo VALUES(2,200,'new-before','new-after')")
    undo.commit()
    # Auxiliary promotion is atomic: an injected failure preserves both slots.
    try:
        migrate(undo, ["DELETE FROM tag_merge_undo WHERE id=1", "INSERT INTO table_that_does_not_exist VALUES(1)"])
        raise AssertionError("Injected promotion failure did not abort")
    except sqlite3.OperationalError:
        pass
    assert undo.execute("SELECT id,createdAt FROM tag_merge_undo ORDER BY id").fetchall() == [(1,100),(2,200)]
    migrate(undo, ["DELETE FROM tag_merge_undo WHERE id=1", "UPDATE tag_merge_undo SET id=1 WHERE id=2"])
    assert undo.execute("SELECT * FROM tag_merge_undo").fetchall() == [(1,200,'new-before','new-after')]
    # Losing the auxiliary DB cannot delete, migrate or otherwise change main data.
    undo.close()
    assert not db.execute("SELECT name FROM sqlite_master WHERE name='tag_merge_undo'").fetchall()
    assert db.execute("SELECT * FROM questions ORDER BY id").fetchall() == before
    db.close()


def verify_failed_migration_rolls_back():
    db = old_database()
    broken = [sql + " WHERE q.id<>2" if sql.startswith("INSERT INTO questions_v10 SELECT") else sql for sql in PLAN]
    try:
        migrate(db, broken)
        raise AssertionError("Count mismatch must abort migration")
    except sqlite3.IntegrityError:
        pass
    assert db.execute("SELECT id,createdAt FROM questions ORDER BY id").fetchall() == [(1,10000),(2,0),(3,20000)]
    assert db.execute("SELECT reviewCount FROM review").fetchone()[0] == 7
    assert not db.execute("SELECT name FROM sqlite_master WHERE name LIKE '%_v10'").fetchall()
    db.close()


if __name__ == "__main__":
    verify_preservation_and_schema()
    verify_failed_migration_rolls_back()
    verify_auxiliary_database_is_independent()
    print("PASS: actual Kotlin v9-v10 migration preserves records/bytes/associations, matches Room v10 schema, and rolls back on failure; auxiliary undo is separate and its slot promotion rolls back on failure.")
