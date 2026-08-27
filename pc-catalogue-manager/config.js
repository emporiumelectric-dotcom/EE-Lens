/*
 * Supabase project configuration.
 *
 * SUPABASE_ANON_KEY is the "publishable" key, not a secret: it is designed to
 * sit in client-side code and be visible to anyone who opens this page. Row
 * Level Security policies on the ee_lens.products / ee_lens.product_photos
 * tables — not this key — decide what an anon or signed-in request can
 * actually read or write. Never put the service_role key here or anywhere
 * else in this app; it bypasses RLS entirely and must stay server-side only
 * (this tool has no server side).
 *
 * Kept in its own file so there is exactly one place to look when rotating
 * the key or pointing this tool at a different Supabase project.
 */

const SUPABASE_URL = 'https://buzidwccluskdkccidev.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_Zm5PI1gxB8ZU6_m4Dydirw_THsgZR7x';

// The schema these tables live in — every REST call must say so explicitly,
// since PostgREST only serves the "public" schema by default.
const SUPABASE_SCHEMA = 'ee_lens';

// Private Storage bucket holding product photos synced to the cloud.
const SUPABASE_PHOTOS_BUCKET = 'ee-lens-photos';
