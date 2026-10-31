-- Existing installations: execute only absent ALTER columns. Do not reimport delta_game.sql.
CREATE TABLE IF NOT EXISTS player_level (
  id int NOT NULL AUTO_INCREMENT,
  name varchar(30) NOT NULL,
  sort_order int NOT NULL DEFAULT 0,
  status tinyint NOT NULL DEFAULT 1,
  deleted tinyint NOT NULL DEFAULT 0,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO player_level(id,name,sort_order,status)
SELECT 1,'高级打手',30,1 WHERE NOT EXISTS(SELECT 1 FROM player_level WHERE id=1);
INSERT INTO player_level(id,name,sort_order,status)
SELECT 2,'金牌打手',10,1 WHERE NOT EXISTS(SELECT 1 FROM player_level WHERE id=2);
INSERT INTO player_level(id,name,sort_order,status)
SELECT 3,'魔王护',20,1 WHERE NOT EXISTS(SELECT 1 FROM player_level WHERE id=3);
INSERT INTO player_level(id,name,sort_order,status)
SELECT DISTINCT p.level,CONCAT('Lv.',p.level),100+p.level,1 FROM player p
WHERE p.level IS NOT NULL AND NOT EXISTS(SELECT 1 FROM player_level l WHERE l.id=p.level);
ALTER TABLE player ADD COLUMN cover_image varchar(512) NOT NULL DEFAULT '';
ALTER TABLE player ADD COLUMN album_images text;
ALTER TABLE player ADD COLUMN story text;
ALTER TABLE player ADD COLUMN voice_url varchar(512) NOT NULL DEFAULT '';
ALTER TABLE player ADD COLUMN voice_duration int NOT NULL DEFAULT 0;
