-- =============================================================
-- V1: 初始建库建表（切片2：会话持久化 + 长期记忆）
-- 数据库：jobagent
-- 字符集：utf8mb4 / utf8mb4_0900_ai_ci（MySQL 8+ 默认，兼容中文与 emoji）
-- =============================================================

CREATE DATABASE IF NOT EXISTS jobagent
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE jobagent;

-- 1. 用户表（为"长期记忆 / 记住我是谁"做准备）
CREATE TABLE IF NOT EXISTS app_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL COMMENT '用户标识（前端 localStorage 生成的 id）',
    nickname   VARCHAR(64)  NULL COMMENT '用户称呼，如"张三"',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表';

-- 2. 会话表（一次连续对话）
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64)  NOT NULL COMMENT '业务键（前端 UUID，API 靠它找回历史）',
    user_id    BIGINT       NULL COMMENT '关联 app_user，可空',
    title      VARCHAR(255) NULL COMMENT '会话标题（取第一条消息前几十字）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_id (session_id),
    KEY idx_user_id (user_id),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '会话表';

-- 3. 消息表（会话里的每一条消息）
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL COMMENT '外键 → chat_session.session_id',
    role       VARCHAR(16) NOT NULL COMMENT 'user / assistant / system / tool',
    content    TEXT        NOT NULL COMMENT '消息内容',
    msg_type   VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT 'normal / summary（压缩摘要）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_id (session_id, id),
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES chat_session (session_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息表';

-- 4. 长期记忆表（未来"记忆抽取"切片用）
CREATE TABLE IF NOT EXISTS user_memory (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    memory_type       VARCHAR(32)   NOT NULL COMMENT 'target_role / weakness / preference / fact',
    content           VARCHAR(1024) NOT NULL COMMENT '记忆内容，如"目标岗位=Java后端"',
    source_session_id VARCHAR(64)   NULL COMMENT '从哪次对话抽取的',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    CONSTRAINT fk_memory_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '长期记忆表';

-- 5. Agent 执行轨迹表（调试 / 后台管理 / 反思切片用）
CREATE TABLE IF NOT EXISTS agent_step (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    session_id   VARCHAR(64) NOT NULL COMMENT '外键 → chat_session.session_id',
    step_index   INT         NOT NULL COMMENT '第几步思考',
    thinking     TEXT        NULL COMMENT '思考内容',
    tool_name    VARCHAR(64) NULL COMMENT '调用的工具名',
    tool_params  TEXT        NULL COMMENT '工具参数（JSON）',
    tool_result  TEXT        NULL COMMENT '工具返回结果',
    final_answer TEXT        NULL COMMENT '若该步直接给出最终答案',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_id (session_id, step_index),
    CONSTRAINT fk_step_session FOREIGN KEY (session_id) REFERENCES chat_session (session_id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Agent 执行轨迹表';
