package com.fanlens.prototype.supabase

/**
 * Supabase project configuration for cloud catalogue sync.
 *
 * ANON_KEY is the "publishable" key, not a secret: Android ships it inside
 * every APK regardless of where in the source tree it lives, and that is by
 * design -- Row Level Security on ee_lens.products / ee_lens.product_photos
 * (anyone can read; only a signed-in user can write) is what actually
 * protects the data, not keeping this key hidden. Never put the
 * service_role key here, or anywhere else in this app -- it bypasses RLS
 * entirely and belongs only on a server this app does not have.
 *
 * Kept in its own file, mirroring pc-catalogue-manager/config.js, so there
 * is exactly one place to look when rotating the key or pointing the app at
 * a different Supabase project.
 */
object SupabaseConfig {
    const val URL = "https://buzidwccluskdkccidev.supabase.co"
    const val ANON_KEY = "sb_publishable_Zm5PI1gxB8ZU6_m4Dydirw_THsgZR7x"

    /** ee_lens.products / ee_lens.product_photos live outside the default "public" schema. */
    const val SCHEMA = "ee_lens"

    /** Private Storage bucket holding product photos synced to the cloud. */
    const val PHOTOS_BUCKET = "ee-lens-photos"
}
