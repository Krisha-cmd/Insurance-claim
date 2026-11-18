-- Demo accounts. Both passwords are "password", stored as BCrypt hashes (strength 10).
--
-- Fixed ids that other services reuse. Keep them in sync:
--   patient1   user id          4e33233c-a255-4c75-963a-6809cd3b0cdd
--   patient1   policyholder_id  a2b56321-c8b8-4159-93d6-3cf613b3c556   <- Policy DB: policyholder.id
--   adjuster1  user id          997bacd0-5b29-4554-b398-52e9f89bfa6c

INSERT INTO users (id, username, email, password_hash, role, status, policyholder_id, full_name)
VALUES
    ('4e33233c-a255-4c75-963a-6809cd3b0cdd', 'patient1', 'patient1@example.com',
     '$2a$10$dPBRGWgbOU/YTqX40xEtheXV/RssJl2Zxbnh/jjFFwTNKbBTdV9Su',
     'POLICYHOLDER', 'ACTIVE', 'a2b56321-c8b8-4159-93d6-3cf613b3c556', 'Asha Verma'),

    ('997bacd0-5b29-4554-b398-52e9f89bfa6c', 'adjuster1', 'adjuster1@example.com',
     '$2a$10$yff.ATA3kYPMlq6aUSLvduBa7Uzc0RUsVbZ3HvKKneVHSfFCsj/MW',
     'ADJUSTER', 'ACTIVE', NULL, 'Rohan Mehta');
