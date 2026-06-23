CREATE TABLE customers (
    id UUID PRIMARY KEY
);

-- Seed data: known customers for PoC validation tests
INSERT INTO customers (id) VALUES
    ('550e8400-e29b-41d4-a716-446655440001'),
    ('550e8400-e29b-41d4-a716-446655440002'),
    ('550e8400-e29b-41d4-a716-446655440003');
