INSERT INTO service_type (id, name, duration_minutes, required_skill) VALUES
    (1, 'Battery replacement',  60, 'BATTERY_FIT'),
    (2, 'Jump-start',           30, 'JUMPSTART'),
    (3, 'Battery health check', 30, 'DIAGNOSTICS');

INSERT INTO zone (id, name) VALUES
    (1, 'Bengaluru South'),
    (2, 'Bengaluru East');

INSERT INTO zone_pincode (pincode, zone_id) VALUES
    ('560034', 1), ('560068', 1), ('560076', 1),
    ('560037', 2), ('560066', 2), ('560103', 2);

INSERT INTO technician (id, name, zone_id, shift_start, shift_end) VALUES
    (1, 'Ravi Kumar',    1, '08:00', '20:00'),
    (2, 'Suresh Reddy',  1, '08:00', '20:00'),
    (3, 'Anil Sharma',   1, '08:00', '20:00'),
    (4, 'Priya Nair',    2, '08:00', '20:00'),
    (5, 'Kiran Rao',     2, '08:00', '20:00'),
    (6, 'Meena Iyer',    2, '08:00', '20:00');

-- Mixed skills. Note: in zone 2 only Kiran can jump-start, which makes it easy to
-- demonstrate the "last free technician" race.
INSERT INTO technician_skill (technician_id, skill) VALUES
    (1, 'BATTERY_FIT'), (1, 'DIAGNOSTICS'),
    (2, 'JUMPSTART'),   (2, 'DIAGNOSTICS'),
    (3, 'BATTERY_FIT'), (3, 'JUMPSTART'), (3, 'DIAGNOSTICS'),
    (4, 'BATTERY_FIT'),
    (5, 'JUMPSTART'),   (5, 'DIAGNOSTICS'),
    (6, 'BATTERY_FIT'), (6, 'DIAGNOSTICS');
